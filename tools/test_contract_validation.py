"""Regression coverage for review failures that plain JSON Schema cannot detect."""
from copy import deepcopy
from pathlib import Path
import unittest
import yaml
from jsonschema import Draft202012Validator
from contract_semantics import validate_fixture_semantics

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = yaml.safe_load((ROOT / 'api-contract/openapi.mobile.yaml').read_text(encoding='utf-8'))


class ContractReviewTests(unittest.TestCase):
    def setUp(self):
        self.contract = deepcopy(CONTRACT)

    def samples(self, path, status='200', method='post'):
        op = self.contract['paths'][path][method]
        body = op['requestBody'] if status == 'request' else op['responses'][status]
        return body['content']['application/json']['examples']

    def assert_semantic_error(self, text):
        self.assertTrue(any(text in e for e in validate_fixture_semantics(self.contract)[1]))

    def test_catalog_type_mismatch(self):
        sample = self.samples('/api/v1/home', method='get')['default']['value']
        sample['units']['items'][0]['lessons'][2]['type'] = 'LESSON'
        self.assert_semantic_error('Catalog title/type')

    def test_product_period_mismatch(self):
        def change(node):
            if isinstance(node, dict):
                if node.get('productId') == 'asl.premium.yearly' and 'period' in node:
                    node['period'] = 'MONTHLY'
                for value in node.values(): change(value)
            elif isinstance(node, list):
                for value in node: change(value)
        change(self.contract['paths'])
        self.assert_semantic_error('productId/period mismatch')

    def test_wrong_source_version(self):
        sample = self.samples('/api/v1/practice/sets')['quiz']['value']
        sample['sourceLessons'][0]['versionId'] = sample['sourceLessons'][1]['versionId']
        self.assert_semantic_error('unknown/mismatched source version')

    def test_unlearned_distractor(self):
        item = self.samples('/api/v1/practice/sets')['quiz']['value']['items'][0]
        item['payload']['options'][1]['signId'] = '9cce405b-996f-464d-9cc4-1d7a7173c01d'
        self.assert_semantic_error('unlearned sign')

    def test_wrong_answer_and_report_target(self):
        sample = self.samples('/api/v1/reports', 'request')['quiz_wrong_answer']['value']
        sample['targetId'] = sample['clientReportId']
        sample['practiceContext']['item']['answerKey']['correctOptionId'] = 'missing_option'
        self.assert_semantic_error('targetId differs')
        self.assert_semantic_error('answer does not resolve')

    def test_same_lesson_versions_do_not_unlock(self):
        sample = self.samples('/api/v1/practice/sets')['quiz']['value']
        sample['sourceLessons'][2]['lessonId'] = sample['sourceLessons'][0]['lessonId']
        self.assert_semantic_error('fewer than three distinct lessons')

    def test_old_request_and_incomplete_grid_rejected(self):
        def validator(name):
            return Draft202012Validator({'$ref': '#/components/schemas/' + name, 'components': self.contract['components']})
        self.assertFalse(validator('PracticeSource').is_valid({'completedLessonIds': []}))
        self.assertTrue(validator('PracticeSource').is_valid({'completedLessons': []}))
        item = self.samples('/api/v1/practice/sets')['quiz_video_grid']['value']['items'][0]
        item['payload']['options'].pop()
        self.assertFalse(validator('QuizPracticeItem').is_valid(item))

    def test_report_context_required_only_for_practice(self):
        sample = self.samples('/api/v1/reports', 'request')['quiz_wrong_answer']['value']
        validator = Draft202012Validator({'$ref': '#/components/schemas/ReportRequest', 'components': self.contract['components']})
        self.assertTrue(validator.is_valid(sample))
        sample['lessonVersionId'] = sample['targetId']
        self.assertFalse(validator.is_valid(sample))
        sample.pop('lessonVersionId')
        sample.pop('practiceContext')
        self.assertFalse(validator.is_valid(sample))


if __name__ == '__main__':
    unittest.main()
