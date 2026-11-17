"""Export Laya-Android to LiteRT (.tflite) in the embeddings-input form of litert-community/Laya-Multilingual-LiteRT.

  tools/laya/.venv-litert/bin/python -I tools/laya/export.py [--windows 1024,512] [--skip-convert] [--onnx]

Outputs in tools/laya/out/ (binaries are gitignored; sizes + sha256 go to out/MANIFEST.json, which is committed):
  laya_android_s{N}_embeds_fp32.tflite   main graph, fp32 weights
  laya_android_s{N}_embeds_wfp16.tflite  main graph, FULLY_CONNECTED weights stored fp16 (+DEQUANTIZE), fp32 compute
  laya_android_act_head_fp32.tflite      act head (optional, see HOST_CONTRACT.md)
  token_embeddings_fp16.bin / .json      [256000,768] float16 row-major token table for the host lookup
  tokenizer.json / tokenizer_config.json / rl_agent_config.json   copied from the checkpoint
  laya_android_s{N}_embeds_fp32.onnx     (--onnx) same graph as ONNX, for a desktop cross-check

Main graph signature `serving_default` (all float32):
  in  inputs_embeds [1,N,768]   token-table rows for the N (right-padded with PAD=0) ids, padding rows included
  in  attention_mask [1,N]      1.0 real token, 0.0 padding
  in  qtype_onehot [1,3]        choice=[1,0,0], score=[0,1,0], noul=[0,0,1]
  out token_logits [1,N]        option score at every position; the host reads the [MASK] positions
  out pooled_cls [1,768]        h[:,0] after the typed head layers (act-head input)

Recipe (same as the community conversion, re-done for this checkpoint): the laya DecisionModel is rewritten as one
explicit fixed-shape module -- embedding LayerNorm on host-gathered rows, 22 ModernBERT layers with RoPE cos/sin baked
per position, padding mask and the |i-j|<=64 sliding-window band as additive float masks (-1e4, fp16-safe), attention
at rank 4, exact GELU, GeGLU MLP; then `h + qtype_onehot @ type_emb`, the two norm-first nn.TransformerEncoderLayer
head layers written out (ReLU FFN, key-padding mask), the scorer applied at every position. It is checked against
laya's own model before conversion (max |logit diff| printed).
"""
import argparse
import hashlib
import json
import math
import os
import shutil
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
MODEL_DIR = os.path.join(HERE, "work", "hf", "laya-android")
OUT = os.path.join(HERE, "out")
NEG = -1e4  # additive mask value; finite in fp16 (the community graphs use the same)


class LayaMain(nn.Module):
    def __init__(self, dm, n):
        super().__init__()
        enc = dm.encoder
        cfg = enc.config
        self.n, self.d = n, cfg.hidden_size
        self.h = cfg.num_attention_heads
        self.hd = self.d // self.h
        self.eps = cfg.norm_eps
        self.emb_norm_w = enc.embeddings.norm.weight
        self.layers = enc.layers
        self.final_norm_w = enc.final_norm.weight
        self.types = list(cfg.layer_types)
        half = cfg.sliding_window  # = local_attention // 2 = 64; HF: |i - j| <= sliding_window
        pos = torch.arange(n, dtype=torch.float32)
        tabs = {}
        for lt in sorted(set(self.types)):
            theta = cfg.rope_parameters[lt]["rope_theta"]
            inv = 1.0 / (theta ** (torch.arange(0, self.hd, 2, dtype=torch.float32) / self.hd))
            fr = pos[:, None] * inv[None, :]
            emb = torch.cat([fr, fr], -1)
            tabs[lt] = (emb.cos()[None, None], emb.sin()[None, None])  # [1,1,N,hd]
        self.register_buffer("cos_full", tabs["full_attention"][0])
        self.register_buffer("sin_full", tabs["full_attention"][1])
        self.register_buffer("cos_slide", tabs["sliding_attention"][0])
        self.register_buffer("sin_slide", tabs["sliding_attention"][1])
        i = torch.arange(n)
        band = ((i[:, None] - i[None, :]).abs() <= half).float()
        self.register_buffer("band_neg", ((1.0 - band) * NEG)[None, None])  # [1,1,N,N]
        self.type_emb_w = dm.type_emb.weight  # [3, d]
        self.head_layers = dm.head.layers
        self.scorer = dm.scorer

    @staticmethod
    def _rot(x, cos, sin):
        x1, x2 = x[..., : x.shape[-1] // 2], x[..., x.shape[-1] // 2:]
        return x * cos + torch.cat([-x2, x1], -1) * sin

    def _ln(self, x, w, b=None, eps=None):
        return F.layer_norm(x, (self.d,), w, b, self.eps if eps is None else eps)

    def forward(self, inputs_embeds, attention_mask, qtype_onehot):
        n, d, H, hd = self.n, self.d, self.h, self.hd
        pad_neg = ((1.0 - attention_mask) * NEG)[:, None, None, :]  # [1,1,1,N]
        mask_full = pad_neg
        mask_slide = pad_neg + self.band_neg
        x = self._ln(inputs_embeds, self.emb_norm_w)
        for li, layer in enumerate(self.layers):
            sliding = self.types[li] == "sliding_attention"
            cos, sin = (self.cos_slide, self.sin_slide) if sliding else (self.cos_full, self.sin_full)
            hn = x if li == 0 else self._ln(x, layer.attn_norm.weight)
            qkv = F.linear(hn, layer.attn.Wqkv.weight).reshape(1, n, 3, H, hd)
            q = qkv[:, :, 0].permute(0, 2, 1, 3)
            k = qkv[:, :, 1].permute(0, 2, 1, 3)
            v = qkv[:, :, 2].permute(0, 2, 1, 3)
            q, k = self._rot(q, cos, sin), self._rot(k, cos, sin)
            s = torch.matmul(q, k.transpose(2, 3)) * (hd ** -0.5) + (mask_slide if sliding else mask_full)
            a = torch.matmul(torch.softmax(s, -1), v).permute(0, 2, 1, 3).reshape(1, n, d)
            x = x + F.linear(a, layer.attn.Wo.weight)
            hm = self._ln(x, layer.mlp_norm.weight)
            inp, gate = F.linear(hm, layer.mlp.Wi.weight).chunk(2, -1)
            x = x + F.linear(F.gelu(inp) * gate, layer.mlp.Wo.weight)
        x = self._ln(x, self.final_norm_w)
        x = x + torch.matmul(qtype_onehot, self.type_emb_w)[:, None, :]
        for layer in self.head_layers:  # nn.TransformerEncoderLayer, norm_first=True, activation=relu
            sa = layer.self_attn
            hn = F.layer_norm(x, (d,), layer.norm1.weight, layer.norm1.bias, layer.norm1.eps)
            qkv = F.linear(hn, sa.in_proj_weight, sa.in_proj_bias).reshape(1, n, 3, sa.num_heads, d // sa.num_heads)
            q = qkv[:, :, 0].permute(0, 2, 1, 3)
            k = qkv[:, :, 1].permute(0, 2, 1, 3)
            v = qkv[:, :, 2].permute(0, 2, 1, 3)
            s = torch.matmul(q, k.transpose(2, 3)) * ((d // sa.num_heads) ** -0.5) + pad_neg
            a = torch.matmul(torch.softmax(s, -1), v).permute(0, 2, 1, 3).reshape(1, n, d)
            x = x + F.linear(a, sa.out_proj.weight, sa.out_proj.bias)
            hn = F.layer_norm(x, (d,), layer.norm2.weight, layer.norm2.bias, layer.norm2.eps)
            x = x + layer.linear2(F.relu(layer.linear1(hn)))
        logits = self.scorer(x).squeeze(-1)  # LayerNorm -> Linear -> GELU(exact) -> Linear
        return {"token_logits": logits, "pooled_cls": x[:, 0]}


class ActHead(nn.Module):
    def __init__(self, dm):
        super().__init__()
        self.act = dm.act_head

    def forward(self, pooled_cls, feats):
        return {"act_logits": self.act(torch.cat([pooled_cls, feats], -1))}


def load_model():
    import laya
    agent = laya.load(MODEL_DIR, device="cpu")
    dm = agent.model.eval().float()
    for name, mod in dm.named_modules():
        if isinstance(mod, nn.TransformerEncoderLayer):
            assert mod.norm_first and mod.activation_relu_or_gelu == 1, name  # pre-norm, ReLU FFN
    return agent, dm


def sample_rows(agent):
    """A few real encoded questions (operation + target on two trees) for the torch-level equivalence check."""
    import candidates as C
    import reference as R
    rows = []
    for name, goal in (("settings_main", "Turn on Bluetooth"), ("app_drawer", "Open WhatsApp")):
        with open(os.path.join(HERE, "trees", name + ".json"), encoding="utf8") as f:
            tree = json.load(f)
        st, qs, _ = C.state_and_questions(tree, goal, ["OPEN_APP Settings"])
        rows += R.encode(agent, st, qs, 1024, 1536)
    return rows


def graph_inputs(table, ids, n, qtype):
    ids_p = np.zeros(n, dtype=np.int64)
    ids_p[:len(ids)] = ids
    emb = table[ids_p].astype(np.float32)[None]
    att = np.zeros((1, n), np.float32)
    att[0, :len(ids)] = 1.0
    oh = np.zeros((1, 3), np.float32)
    oh[0, qtype] = 1.0
    return emb, att, oh


def check_torch(agent, dm, table, n):
    from laya.common import collate_items
    w = LayaMain(dm, n).eval()
    worst = 0.0
    for e in sample_rows(agent):
        if len(e["ids"]) > n:
            continue
        b = collate_items([[{"ids": e["ids"], "markers": e["markers"], "qtype": e["qtype"]}]], 0)
        with torch.no_grad():
            ref, _ = dm(b["input_ids"], b["attention_mask"], b["marker_pos"], b["marker_mask"], b["qtype"])
            emb, att, oh = graph_inputs(table, e["ids"], n, e["qtype"])
            out = w(torch.from_numpy(emb), torch.from_numpy(att), torch.from_numpy(oh))
        got = out["token_logits"][0, e["markers"]]
        diff = float((got - ref[0, :len(e["markers"])]).abs().max())
        worst = max(worst, diff)
        print("  torch check n=%d %-9s len=%4d k=%2d max|dlogit|=%.2e" % (n, e["qid"], len(e["ids"]), len(e["markers"]), diff))
    return w, worst


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 24), b""):
            h.update(chunk)
    return h.hexdigest()


def export_table():
    from safetensors import safe_open
    src = os.path.join(MODEL_DIR, "model.safetensors")
    with safe_open(src, "pt") as f:
        key = next(k for k in f.keys() if k.endswith("embeddings.tok_embeddings.weight"))
        t = f.get_tensor(key)
    assert t.shape == (256000, 768), t.shape
    t16 = t.to(torch.float16)
    rt = float((t16.float() - t.float()).abs().max())
    arr = t16.numpy()
    path = os.path.join(OUT, "token_embeddings_fp16.bin")
    arr.astype("<f2").tofile(path)
    meta = {"shape": [256000, 768], "dtype": "float16", "byte_order": "little", "layout": "row-major",
            "filename": "token_embeddings_fp16.bin", "size_bytes": os.path.getsize(path), "sha256": sha256(path),
            "source_repository": "ByunByun/laya-android", "source_tensor": key, "source_dtype": str(t.dtype),
            "source_checkpoint_sha256": sha256(src), "max_abs_fp32_minus_fp16_roundtrip": rt,
            "pad_id": 0, "pad_policy": "Gather token row 0 for every padded position"}
    with open(os.path.join(OUT, "token_embeddings.json"), "w") as f:
        json.dump(meta, f, indent=1)
    print("token table:", meta["size_bytes"], "bytes, source dtype", t.dtype, "roundtrip err", rt)
    return arr


def to_wfp16(src, dst):
    from ai_edge_quantizer import quantizer
    recipe = [{"regex": ".*", "operation": "FULLY_CONNECTED", "algorithm_key": "float_casting",
               "op_config": {"weight_tensor_config": {"num_bits": 16, "dtype": "FLOAT", "granularity": "TENSORWISE"},
                             "compute_precision": "FLOAT", "explicit_dequantize": True}}]
    qt = quantizer.Quantizer(src)
    qt.load_quantization_recipe(recipe)
    res = qt.quantize()
    res.export_model(dst, overwrite=True)


def convert(module, kwargs, path):
    import litert_torch
    t0 = time.time()
    m = litert_torch.convert(module.eval(), sample_kwargs=kwargs)
    m.export(path)
    print("  wrote %s (%.1f MB, %.0fs)" % (os.path.basename(path), os.path.getsize(path) / 1e6, time.time() - t0))


def convert_multi(dm, table, windows, path):
    """One .tflite with one signature per window (`s256`, `s512`, `s1024`), weights stored once."""
    import litert_torch
    t0 = time.time()
    conv = litert_torch
    first = True
    for n in windows:
        emb, att, oh = graph_inputs(table, [2, 1], n, 0)
        kw = {"inputs_embeds": torch.from_numpy(emb), "attention_mask": torch.from_numpy(att),
              "qtype_onehot": torch.from_numpy(oh)}
        w = LayaMain(dm, n).eval()
        conv = conv.signature("s%d" % n, w, sample_kwargs=kw) if not first else litert_torch.signature("s%d" % n, w, sample_kwargs=kw)
        first = False
    m = conv.convert()
    m.export(path)
    print("  wrote %s (%.1f MB, %.0fs)" % (os.path.basename(path), os.path.getsize(path) / 1e6, time.time() - t0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--windows", default="1024,512")
    ap.add_argument("--skip-convert", action="store_true", help="only the torch equivalence check + table")
    ap.add_argument("--onnx", action="store_true", help="also write an ONNX copy of each main graph")
    ap.add_argument("--multi", default="", help="e.g. 256,512,1024: also write laya_android_multi_embeds_{fp32,wfp16}"
                    ".tflite with one signature per window (shared weights)")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    agent, dm = load_model()
    table = export_table()
    for fn in ("tokenizer/tokenizer.json", "tokenizer/tokenizer_config.json", "rl_agent_config.json"):
        shutil.copy(os.path.join(MODEL_DIR, fn), os.path.join(OUT, os.path.basename(fn)))
    torch_check = {}
    for n in [int(x) for x in a.windows.split(",")]:
        w, worst = check_torch(agent, dm, table, n)
        torch_check[n] = worst
        assert worst < 1e-3, "explicit rewrite diverges from laya's model: %g" % worst
        if a.skip_convert:
            continue
        emb, att, oh = graph_inputs(table, [2, 1], n, 0)
        kw = {"inputs_embeds": torch.from_numpy(emb), "attention_mask": torch.from_numpy(att),
              "qtype_onehot": torch.from_numpy(oh)}
        base = os.path.join(OUT, "laya_android_s%d_embeds" % n)
        convert(w, kw, base + "_fp32.tflite")
        to_wfp16(base + "_fp32.tflite", base + "_wfp16.tflite")
        print("  wrote %s (%.1f MB)" % (os.path.basename(base + "_wfp16.tflite"), os.path.getsize(base + "_wfp16.tflite") / 1e6))
        if a.onnx:
            torch.onnx.export(w, (kw["inputs_embeds"], kw["attention_mask"], kw["qtype_onehot"]), base + "_fp32.onnx",
                              input_names=list(kw), output_names=["token_logits", "pooled_cls"], opset_version=18,
                              dynamo=False)
    if a.multi and not a.skip_convert:
        base = os.path.join(OUT, "laya_android_multi_embeds")
        convert_multi(dm, table, [int(x) for x in a.multi.split(",")], base + "_fp32.tflite")
        to_wfp16(base + "_fp32.tflite", base + "_wfp16.tflite")
        print("  wrote %s (%.1f MB)" % (os.path.basename(base + "_wfp16.tflite"), os.path.getsize(base + "_wfp16.tflite") / 1e6))
    if not a.skip_convert:
        act = ActHead(dm)
        convert(act, {"pooled_cls": torch.zeros(1, 768), "feats": torch.zeros(1, 4)},
                os.path.join(OUT, "laya_android_act_head_fp32.tflite"))
    with open(os.path.join(OUT, "export_torch_check.json"), "w") as f:
        json.dump({str(k): v for k, v in torch_check.items()}, f)


if __name__ == "__main__":
    main()
