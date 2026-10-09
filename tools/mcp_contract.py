"""Check that the bundled assistant guide covers every registered MCP tool."""
from pathlib import Path
import re


def verify(root: Path):
    source = root / "mcp/src/jvmMain/kotlin/dev/ruleblend/mcp/RuleblendServer.kt"
    guide = root / "mcp/src/jvmMain/resources/dev/ruleblend/mcp/skill/SKILL.md"
    tools = re.findall(r'\btool\s*\(\s*(?:name\s*=\s*)?"([a-z_]+)"', source.read_text(encoding="utf-8"))
    documented = set(re.findall(r"`([a-z_]+)`", guide.read_text(encoding="utf-8")))
    assert tools, "No registered tools found"
    assert len(tools) == len(set(tools)), "Duplicate MCP tool registration"
    missing = set(tools) - documented
    assert not missing, f"Undocumented tools: {', '.join(sorted(missing))}"
    print(f"MCP contract: {len(tools)} unique registered tools documented")


if __name__ == "__main__":
    verify(Path(__file__).resolve().parent.parent)
