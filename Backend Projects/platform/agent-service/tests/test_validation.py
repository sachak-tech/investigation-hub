import hashlib
import unittest
from uuid import uuid4
from agent.models import Analysis, Citation, ModelAnalysis, finalise_analysis, validate_analysis
from agent.model import prompt_context, analyse


class CitationValidation(unittest.TestCase):
    def setUp(self):
        self.document_id = uuid4()
        content = "Synthetic statement\nSalary: 5000 synthetic units."
        self.context = {"stepId": "bank_document_review", "step": {"completionEvidence": ["document_review"]},
                        "documents": [{"documentId": str(self.document_id), "title": "Statement",
                                       "content": content, "sha256": hashlib.sha256(content.encode()).hexdigest()}]}

    def result(self, **changes):
        values = {"findings": "The supplied synthetic source records a salary claim of 5000 units. The source has not been independently authenticated.",
                  "citations": [Citation(evidenceType="document_review", documentId=self.document_id,
                                         startLine=2, endLine=2, excerpt="Salary: 5000 synthetic units.")],
                  "gaps": [], "recommendation": "READY_FOR_REVIEW"}
        values.update(changes)
        return Analysis(**values)

    def test_exact_source_citation(self):
        validate_analysis(self.context, self.result())

    def test_fabricated_document_is_rejected(self):
        value = self.result()
        value.citations[0].documentId = uuid4()
        with self.assertRaises(ValueError): validate_analysis(self.context, value)

    def test_modified_quote_is_rejected(self):
        value = self.result()
        value.citations[0].excerpt = "Salary: 9000 units."
        with self.assertRaises(ValueError): validate_analysis(self.context, value)

    def test_ready_requires_evidence(self):
        with self.assertRaises(ValueError): validate_analysis(self.context, self.result(citations=[]))

    def test_missing_information_can_remain_uncited(self):
        validate_analysis(self.context, self.result(citations=[], gaps=["A bank statement is missing"], recommendation="NEEDS_INFORMATION"))

    def test_service_marks_missing_required_citations_as_needing_information(self):
        raw = ModelAnalysis(findings=self.result().findings, citations=[], gaps=[])
        result = finalise_analysis(self.context, raw)
        self.assertEqual(result.recommendation, "NEEDS_INFORMATION")
        self.assertTrue(result.gaps)
        validate_analysis(self.context, result)

    def test_source_budget_and_digest_are_enforced(self):
        self.context["documents"][0]["content"] = "Changed source"
        with self.assertRaises(ValueError): prompt_context(self.context)
        self.context["documents"] = []
        with self.assertRaises(ValueError): prompt_context(self.context)

    def test_model_input_budget_rejects_before_inference(self):
        content = "x" * 15000
        self.context["documents"][0]["content"] = content
        self.context["documents"][0]["sha256"] = hashlib.sha256(content.encode()).hexdigest()
        with self.assertRaisesRegex(ValueError, "MODEL_INPUT_BUDGET_EXCEEDED"):
            analyse(self.context)


if __name__ == "__main__": unittest.main()
