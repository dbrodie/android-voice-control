"""Write tools/laya/out/MANIFEST.json: size + sha256 of every exported artifact (the binaries are not committed).

  tools/laya/.venv/bin/python -I tools/laya/manifest.py
"""
import hashlib
import json
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
ROLE = {
    "laya_android_multi_embeds_wfp16.tflite": "RECOMMENDED main graph: signatures s256/s512/s1024/s2048, shared fp16 FC weights, fp32 compute",
    "laya_android_multi_embeds_fp32.tflite": "main graph, same signatures, fp32 weights",
    "laya_android_s1024_embeds_wfp16.tflite": "main graph, window 1024, signature serving_default",
    "laya_android_s1024_embeds_fp32.tflite": "main graph, window 1024, fp32 weights",
    "laya_android_s512_embeds_wfp16.tflite": "main graph, window 512",
    "laya_android_s512_embeds_fp32.tflite": "main graph, window 512, fp32 weights",
    "laya_android_s256_embeds_wfp16.tflite": "main graph, window 256",
    "laya_android_s256_embeds_fp32.tflite": "main graph, window 256, fp32 weights",
    "laya_android_act_head_fp32.tflite": "act head (not needed; act_probability saturates at 1.0)",
    "token_embeddings_fp16.bin": "[256000,768] float16 LE row-major token table (host lookup)",
    "token_embeddings.json": "token table metadata",
    "tokenizer.json": "tokenizer (ignore its stale truncation field)",
    "tokenizer_config.json": "tokenizer config",
    "rl_agent_config.json": "checkpoint config incl. temperatures",
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 24), b""):
            h.update(chunk)
    return h.hexdigest()


def pkg_versions():
    out = {}
    for venv, pkgs in ((".venv-litert", ("litert-torch", "ai-edge-litert", "ai-edge-quantizer", "torch", "transformers", "laya")),
                       (".venv", ("laya", "torch", "transformers", "tokenizers"))):
        try:
            txt = subprocess.run(["uv", "pip", "list", "--python", os.path.join(HERE, venv, "bin", "python")],
                                 capture_output=True, text=True, check=True).stdout
        except Exception:  # noqa: BLE001
            continue
        for line in txt.splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[0] in pkgs:
                out["%s:%s" % (venv, parts[0])] = parts[1]
    return out


def main():
    files = {}
    for name in sorted(os.listdir(OUT)):
        p = os.path.join(OUT, name)
        if name == "MANIFEST.json" or not os.path.isfile(p) or name not in ROLE:
            continue
        files[name] = {"bytes": os.path.getsize(p), "sha256": sha256(p), "role": ROLE[name]}
    rec = ["laya_android_multi_embeds_wfp16.tflite", "token_embeddings_fp16.bin", "tokenizer.json", "rl_agent_config.json"]
    manifest = {
        "source": {"model": "ByunByun/laya-android", "base": "convaiinnovations/laya@7b928d8 / multilingual",
                   "checkpoint_sha256": json.load(open(os.path.join(OUT, "token_embeddings.json")))["source_checkpoint_sha256"]},
        "converter": "tools/laya/export.py (litert-torch convert, ai-edge-quantizer float_casting on FULLY_CONNECTED)",
        "versions": pkg_versions(),
        "recommended_phone_set": rec,
        "recommended_phone_set_bytes": sum(files[f]["bytes"] for f in rec if f in files),
        "files": files,
    }
    with open(os.path.join(OUT, "MANIFEST.json"), "w") as f:
        json.dump(manifest, f, indent=1)
        f.write("\n")
    for k, v in files.items():
        print("%-42s %12d %s" % (k, v["bytes"], v["sha256"][:16]))
    print("recommended set:", manifest["recommended_phone_set_bytes"], "bytes")


if __name__ == "__main__":
    main()
