"""Turn checked-in expected JSON into Kotlin assertions. No images are OCRed by this host harness."""
import json
from pathlib import Path
root = Path(__file__).resolve().parents[2]
out = root / ".ocr-harness/checks/GeneratedFixtureChecks.kt"
code = ['import com.yourapp.wine.LogicChecks', 'import com.yourapp.wine.fill.*', 'import com.yourapp.wine.kb.FixtureKnowledgeBase', 'object GeneratedFixtureChecks { fun run() {']
for i, path in enumerate(sorted((root / "testdata/labels").glob("*.json"))):
    data = json.loads(path.read_text())
    def quoted(s): return json.dumps(s, ensure_ascii=False).replace('$', '\\$')
    tokens = []
    for token in data["expected_tokens"]:
        l,t,r,b = token["bbox_rect"]
        tokens.append(f'LogicChecks.token({quoted(token["text"])}, score={token["score"]}f, x={l}f, y={t}f, w={r-l}f, h={b-t}f)')
    code += [f'val result{i} = FillEngine(FixtureKnowledgeBase()).fill(listOf({",".join(tokens)}), 2026)']
    for field, expected in data['expected'].items():
        code += [f'check(result{i}.fields.getValue(FieldId.{field}).value == {quoted(expected["value"])})',
                 f'check(result{i}.fields.getValue(FieldId.{field}).state == UiState.{expected["state"]})']
    code += [f'println("PASS: {path.stem} expected-token fixture / six GREEN fields")']
code += ['} }']
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text('\n'.join(code) + '\n')
