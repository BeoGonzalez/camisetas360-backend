"""Read Maven/JaCoCo evidence without rebuilding or contacting external systems."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SERVICES = ("auth", "catalog", "carrito", "orders", "notifications", "recommendation")


def main():
    results = {}
    for service in SERVICES:
        target = ROOT / service / "target"
        suites = [ET.parse(path).getroot() for folder in ("surefire-reports", "failsafe-reports")
                  for path in (target / folder).glob("TEST-*.xml")]
        assert suites, "No test results for " + service
        totals = {key: sum(int(suite.attrib.get(key, 0)) for suite in suites)
                  for key in ("tests", "failures", "errors", "skipped")}
        assert totals["tests"] > 0 and all(totals[key] == 0 for key in ("failures", "errors", "skipped")), service + " has failed/skipped tests"
        report = target / "site/jacoco"
        assert (report / "index.html").is_file(), "Missing JaCoCo HTML for " + service
        root = ET.parse(report / "jacoco.xml").getroot()
        totals["coverage"] = {counter.attrib["type"]: {key: int(counter.attrib[key]) for key in ("covered", "missed")}
                              for counter in root.findall("counter")}
        raw_log = (ROOT / service / "security-verify.log").read_bytes()
        log = raw_log.decode("utf-16" if raw_log.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8", errors="replace")
        assert "BUILD SUCCESS" in log and "BUILD FAILURE" not in log, "Incomplete full build for " + service
        assert "do not match with execution data" not in log, "Stale coverage for " + service
        totals["clean_verify"] = "BUILD SUCCESS"
        totals["html_xml"] = True
        results[service] = totals
    print(json.dumps(results))


if __name__ == "__main__":
    main()
