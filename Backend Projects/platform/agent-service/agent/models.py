from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class RunRequest(StrictModel):
    runId: UUID
    caseId: UUID
    stepId: str = Field(pattern=r"^[a-z][a-z0-9_]*$")
    expectedCaseRevision: int = Field(ge=1)
    expectedPlaybookRevision: int = Field(ge=1)


class Citation(StrictModel):
    evidenceType: str = Field(min_length=1, max_length=120)
    documentId: UUID
    startLine: int = Field(ge=1)
    endLine: int = Field(ge=1)
    excerpt: str = Field(min_length=1, max_length=12000)


class ModelAnalysis(StrictModel):
    findings: str = Field(min_length=80, max_length=20000,
                          description="A substantive 2-4 sentence source-grounded summary with uncertainties, never a status label")
    citations: list[Citation] = Field(max_length=50)
    gaps: list[str] = Field(max_length=20)


class Analysis(ModelAnalysis):
    recommendation: Literal["READY_FOR_REVIEW", "NEEDS_INFORMATION"]


def finalise_analysis(context: dict, raw: ModelAnalysis) -> Analysis:
    gaps = list(raw.gaps)
    supplied = {citation.evidenceType for citation in raw.citations}
    for evidence_type in context["step"].get("completionEvidence", []):
        if evidence_type not in supplied:
            gaps.append("No supported citation for required evidence type: " + evidence_type)
    return Analysis(findings=raw.findings, citations=raw.citations, gaps=gaps,
                    recommendation="NEEDS_INFORMATION" if gaps else "READY_FOR_REVIEW")


def validate_analysis(context: dict, analysis: Analysis) -> None:
    """Validate traceability; this does not establish source authenticity or semantic truth."""
    if not analysis.findings.strip() or any(not gap.strip() for gap in analysis.gaps):
        raise ValueError("Findings and gap descriptions cannot be blank")
    documents = {str(doc["documentId"]): doc for doc in context["documents"]}
    requirements = set(context["step"].get("completionEvidence", []))
    supplied = set()
    for citation in analysis.citations:
        document = documents.get(str(citation.documentId))
        if document is None:
            raise ValueError("Citation document is absent from the supplied case sources")
        if citation.evidenceType not in requirements:
            raise ValueError("Citation evidenceType must match a step completion requirement")
        lines = document["content"].split("\n")
        if citation.endLine < citation.startLine or citation.endLine > len(lines):
            raise ValueError("Citation line range is outside its source")
        if citation.excerpt != "\n".join(lines[citation.startLine - 1:citation.endLine]):
            raise ValueError("Citation excerpt must exactly match the cited source lines")
        supplied.add(citation.evidenceType)
    if analysis.recommendation == "READY_FOR_REVIEW" and (analysis.gaps or not requirements <= supplied):
        raise ValueError("Ready results need every required evidence type and no gaps")
    if analysis.recommendation == "NEEDS_INFORMATION" and not analysis.gaps:
        raise ValueError("Needs-information results must describe missing information")
