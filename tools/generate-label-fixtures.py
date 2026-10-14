#!/usr/bin/env python3
"""Generate three synthetic printed crops and hand-declared expected tokens, NOT OCR measurements."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1] / "testdata/labels"
root.mkdir(parents=True, exist_ok=True)
font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"
labels = [
    ("margaux", ["Château Margaux", "Pavillon Rouge", "Margaux", "2018"],
     ["Château Margaux", "Pavillon Rouge", "France", "Bordeaux", "Margaux", "2018"]),
    ("barolo", ["Vietti", "Castiglione", "Barolo", "2019"],
     ["Vietti", "Castiglione", "Italy", "Piedmont", "Barolo", "2019"]),
    ("napa", ["Robert Mondavi", "Reserve Cabernet Sauvignon", "Napa Valley", "2020"],
     ["Robert Mondavi", "Reserve Cabernet Sauvignon", "United States", "California", "Napa Valley", "2020"]),
]
for slug, lines, expected in labels:
    image = Image.new("RGB", (840, 1120), (241, 235, 218))
    draw = ImageDraw.Draw(image)
    draw.rectangle((30, 30, 810, 1090), outline=(30, 25, 20), width=4)
    tokens = []
    for text, y in zip(lines, [200, 390, 600, 830]):
        font_size = 64
        while True:
            font = ImageFont.truetype(font_path, font_size)
            bounds = draw.textbbox((0, 0), text, font=font)
            if bounds[2] - bounds[0] < 730:
                break
            font_size -= 1
        width = bounds[2] - bounds[0]
        x = (840 - width) / 2
        draw.text((x, y), text, font=font, fill=(25, 20, 18))
        bbox = draw.textbbox((x, y), text, font=font)
        normalized = [bbox[0]/840, bbox[1]/1120, bbox[2]/840, bbox[3]/1120]
        tokens.append({"text": text, "score": 0.95, "engine_id": "mlkit", "variant_id": "V0",
                       "bbox_orig": normalized, "bbox_rect": normalized})
    image.save(root / f"{slug}.png", optimize=True)
    data = {"schema": "wine-label-fixture/v1", "synthetic": True, "image": f"{slug}.png",
            "coordinate_note": "Expected tokens describe this printed crop (identity homography); scores are synthetic test inputs, not measured OCR confidence.",
            "expected": dict(zip(["PRODUCER", "WINE_NAME", "COUNTRY", "REGION", "SUBREGION", "VINTAGE"],
                                 [{"value": value, "state": "GREEN"} for value in expected])),
            "expected_tokens": tokens}
    (root / f"{slug}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    print(f"Generated {slug}: synthetic crop + expected JSON")
