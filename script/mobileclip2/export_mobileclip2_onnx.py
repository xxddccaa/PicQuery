#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.onnx
from huggingface_hub import hf_hub_download


PICQUERY_ROOT = Path(__file__).resolve().parents[2]
ML_MOBILECLIP_ROOT = PICQUERY_ROOT.parent / "ml-mobileclip"
OPEN_CLIP_ROOT = ML_MOBILECLIP_ROOT / ".work" / "open_clip"

if str(ML_MOBILECLIP_ROOT) not in sys.path:
    sys.path.insert(0, str(ML_MOBILECLIP_ROOT))
if str(OPEN_CLIP_ROOT / "src") not in sys.path:
    sys.path.insert(0, str(OPEN_CLIP_ROOT / "src"))

import open_clip  # noqa: E402
from mobileclip.modules.common.mobileone import reparameterize_model  # noqa: E402


CHECKPOINT_FILENAMES = {
    "MobileCLIP2-S0": "mobileclip2_s0.pt",
    "MobileCLIP2-S2": "mobileclip2_s2.pt",
    "MobileCLIP2-B": "mobileclip2_b.pt",
    "MobileCLIP2-S3": "mobileclip2_s3.pt",
    "MobileCLIP2-S4": "mobileclip2_s4.pt",
    "MobileCLIP2-L-14": "mobileclip2_l14.pt",
}

ZERO_MEAN_UNIT_STD_MODELS = {
    "MobileCLIP2-S0",
    "MobileCLIP2-S2",
    "MobileCLIP2-B",
}


class ImageEncoderWrapper(torch.nn.Module):
    def __init__(self, model: torch.nn.Module) -> None:
        super().__init__()
        self.model = model

    def forward(self, pixel_values: torch.Tensor) -> torch.Tensor:
        return self.model.encode_image(pixel_values)


class TextEncoderWrapper(torch.nn.Module):
    def __init__(self, model: torch.nn.Module) -> None:
        super().__init__()
        self.model = model

    def forward(self, input_ids: torch.Tensor) -> torch.Tensor:
        return self.model.encode_text(input_ids)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export MobileCLIP2 checkpoints to ONNX for PicQuery.")
    parser.add_argument("--model-name", default="MobileCLIP2-B", choices=sorted(CHECKPOINT_FILENAMES))
    parser.add_argument("--out-dir", required=True, help="Directory for mobileclip2_image.onnx and mobileclip2_text.onnx")
    parser.add_argument("--checkpoint", help="Optional local checkpoint path; defaults to the official Hugging Face checkpoint")
    parser.add_argument("--opset", type=int, default=17)
    parser.add_argument("--batch-size", type=int, default=2)
    parser.add_argument("--fixed-batch", type=int, default=1, help="Export fixed batch models when > 0; use 0 to keep dynamic batch.")
    parser.add_argument("--verify", action="store_true")
    return parser.parse_args()


def resolve_checkpoint(model_name: str, checkpoint: str | None) -> Path:
    if checkpoint:
        path = Path(checkpoint).expanduser().resolve()
        if not path.exists():
            raise FileNotFoundError(f"Checkpoint not found: {path}")
        return path

    filename = CHECKPOINT_FILENAMES[model_name]
    return Path(
        hf_hub_download(
            repo_id=f"apple/{model_name}",
            filename=filename,
        )
    )


def create_model(model_name: str, checkpoint_path: Path) -> torch.nn.Module:
    model_kwargs = {}
    if model_name in ZERO_MEAN_UNIT_STD_MODELS:
        model_kwargs = {
            "image_mean": (0.0, 0.0, 0.0),
            "image_std": (1.0, 1.0, 1.0),
        }

    model, _, _ = open_clip.create_model_and_transforms(
        model_name,
        pretrained=str(checkpoint_path),
        **model_kwargs,
    )
    model.eval()
    model = reparameterize_model(model)
    model.eval()
    return model


def read_model_config(model_name: str) -> dict:
    config_path = ML_MOBILECLIP_ROOT / "mobileclip2" / "model_configs" / f"{model_name}.json"
    return json.loads(config_path.read_text())


def export_onnx(
    model_name: str,
    out_dir: Path,
    opset: int,
    batch_size: int,
    checkpoint: str | None,
    fixed_batch: int,
) -> dict[str, int]:
    checkpoint_path = resolve_checkpoint(model_name, checkpoint)
    config = read_model_config(model_name)
    model = create_model(model_name, checkpoint_path)
    out_dir.mkdir(parents=True, exist_ok=True)

    image_encoder = ImageEncoderWrapper(model)
    text_encoder = TextEncoderWrapper(model)
    image_encoder.eval()
    text_encoder.eval()

    image_size = int(config["vision_cfg"]["image_size"])
    context_length = int(config["text_cfg"]["context_length"])
    vocab_size = int(config["text_cfg"]["vocab_size"])

    export_batch = fixed_batch if fixed_batch > 0 else batch_size
    dummy_image = torch.randn(export_batch, 3, image_size, image_size, dtype=torch.float32)
    dummy_tokens = torch.randint(0, vocab_size, (export_batch, context_length), dtype=torch.long)

    image_out = out_dir / "mobileclip2_image.onnx"
    text_out = out_dir / "mobileclip2_text.onnx"

    torch.onnx.export(
        image_encoder,
        dummy_image,
        image_out,
        input_names=["pixel_values"],
        output_names=["image_embeds"],
        dynamic_axes=None if fixed_batch > 0 else {"pixel_values": {0: "batch"}, "image_embeds": {0: "batch"}},
        opset_version=opset,
        do_constant_folding=True,
        training=torch.onnx.TrainingMode.EVAL,
        dynamo=False,
    )
    torch.onnx.export(
        text_encoder,
        dummy_tokens,
        text_out,
        input_names=["input_ids"],
        output_names=["text_embeds"],
        dynamic_axes=None if fixed_batch > 0 else {"input_ids": {0: "batch"}, "text_embeds": {0: "batch"}},
        opset_version=opset,
        do_constant_folding=True,
        training=torch.onnx.TrainingMode.EVAL,
        dynamo=False,
    )

    with torch.no_grad():
        image_dim = int(model.encode_image(dummy_image[:1]).shape[-1])
        text_dim = int(model.encode_text(dummy_tokens[:1]).shape[-1])

    metadata = {
        "model_name": model_name,
        "checkpoint_path": str(checkpoint_path),
        "image_embedding_dim": image_dim,
        "text_embedding_dim": text_dim,
        "image_size": image_size,
        "context_length": context_length,
        "fixed_batch": fixed_batch,
        "image_model": str(image_out),
        "text_model": str(text_out),
    }
    (out_dir / "export-info.json").write_text(json.dumps(metadata, indent=2))
    return metadata


def verify_exports(out_dir: Path, model_name: str, checkpoint: str | None) -> None:
    checkpoint_path = resolve_checkpoint(model_name, checkpoint)
    config = read_model_config(model_name)
    model = create_model(model_name, checkpoint_path)
    image_size = int(config["vision_cfg"]["image_size"])
    context_length = int(config["text_cfg"]["context_length"])
    vocab_size = int(config["text_cfg"]["vocab_size"])

    image_input = torch.randn(2, 3, image_size, image_size, dtype=torch.float32)
    text_input = torch.randint(0, vocab_size, (2, context_length), dtype=torch.long)

    with torch.no_grad():
        torch_image = model.encode_image(image_input).cpu().numpy()
        torch_text = model.encode_text(text_input).cpu().numpy()

    ort_image = ort.InferenceSession(str(out_dir / "mobileclip2_image.onnx"), providers=["CPUExecutionProvider"])
    ort_text = ort.InferenceSession(str(out_dir / "mobileclip2_text.onnx"), providers=["CPUExecutionProvider"])

    onnx_image = ort_image.run(None, {"pixel_values": image_input.cpu().numpy()})[0]
    onnx_text = ort_text.run(None, {"input_ids": text_input.cpu().numpy().astype(np.int64)})[0]

    image_diff = float(np.max(np.abs(torch_image - onnx_image)))
    text_diff = float(np.max(np.abs(torch_text - onnx_text)))
    print(f"verify image max_abs_diff={image_diff:.6f}")
    print(f"verify text  max_abs_diff={text_diff:.6f}")

    if image_diff > 1e-3 or text_diff > 1e-3:
        raise RuntimeError("ONNX verification drift is too large")


def main() -> None:
    args = parse_args()
    out_dir = Path(args.out_dir).expanduser().resolve()
    metadata = export_onnx(
        model_name=args.model_name,
        out_dir=out_dir,
        opset=args.opset,
        batch_size=args.batch_size,
        checkpoint=args.checkpoint,
        fixed_batch=args.fixed_batch,
    )
    print(json.dumps(metadata, indent=2))

    if args.verify:
        verify_exports(out_dir, args.model_name, args.checkpoint)


if __name__ == "__main__":
    main()
