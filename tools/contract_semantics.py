"""Cross-reference checks for embedded contract fixtures, not runtime business validation."""
from urllib.parse import urlsplit


def validate_fixture_semantics(contract):
    errors = []
    checks = 0

    def check(ok, message):
        nonlocal checks
        checks += 1
        if not ok:
            errors.append(message)

    def examples(path, method='get', status='200'):
        operation = contract['paths'][path][method]
        body = operation['requestBody'] if status == 'request' else operation['responses'][status]
        return {k: v['value'] for k, v in body['content']['application/json']['examples'].items()}

    lessons = {(v['lessonId'], v['versionId']): v for v in examples('/api/v1/lessons/{lessonId}').values()}

    def walk(value):
        if isinstance(value, dict):
            yield value
            for child in value.values():
                yield from walk(child)
        elif isinstance(value, list):
            for child in value:
                yield from walk(child)

    for value in walk(contract['paths']):
        if {'id', 'currentVersionId', 'prerequisiteLessonIds'} <= value.keys():
            lesson = lessons.get((value['id'], value['currentVersionId']))
            check(lesson is not None, 'Catalog references a version missing from lesson fixtures')
            if lesson:
                check((value['title'], value['type']) == (lesson['title'], lesson['type']),
                      'Catalog title/type differs from lesson version: ' + value['id'])
        if {'productId', 'period'} <= value.keys() and value['productId'].startswith('asl.premium.'):
            period = value['productId'].rsplit('.', 1)[-1].upper()
            check(value['period'] == period, 'Sample productId/period mismatch: ' + value['productId'])
        if {'id', 'name', 'isPremium', 'signCount', 'cover'} <= value.keys():
            url = urlsplit(value.get('shareUrl', ''))
            check(url.scheme == 'https' and bool(url.netloc) and url.path == '/collections/' + value['id']
                  and not url.query and not url.fragment, 'Collection shareUrl does not identify its catalog collection')

    def source_signs(sources, label):
        pairs = [(s['lessonId'], s['versionId']) for s in sources]
        check(len(pairs) == len(set(pairs)), label + ': duplicate source pairs')
        result = {}
        images = set()
        for pair in pairs:
            check(pair in lessons, label + ': unknown/mismatched source version')
            if pair in lessons:
                lesson = lessons[pair]
                result.update({s['id']: s for s in lesson['signs']})
                for exercise in lesson['exercises']:
                    if exercise['type'] == 'VIDEO_TO_IMAGE':
                        for option in exercise['payload']['options']:
                            asset = exercise['media'][option['imageSlot']]['assetId']
                            images.update((s['id'], asset) for s in lesson['signs'] if s['name'] == option['label'])
        return result, images

    def check_quiz(item, signs, images, label):
        options = item['payload']['options']
        option_ids = [o['id'] for o in options]
        option_signs = [o['signId'] for o in options]
        check(len(option_ids) == len(set(option_ids)), label + ': duplicate option IDs')
        check(len(option_signs) == len(set(option_signs)), label + ': duplicate option signs')
        check(item['signId'] in signs and set(option_signs) <= signs.keys(), label + ': quiz contains unlearned sign')
        answer = [o for o in options if o['id'] == item['answerKey']['correctOptionId']]
        check(len(answer) == 1 and answer[0]['signId'] == item['signId'], label + ': answer does not resolve to target sign')
        check(item['payload']['layout'] != 'GRID_2X2' or len(options) == 4, label + ': grid requires four options')
        if item['signId'] in signs:
            target = signs[item['signId']]
            if item['type'] == 'WORD_TO_VIDEO':
                check(item['payload']['word'] == target['name'], label + ': target word differs from source snapshot')
            else:
                check(item['payload']['video']['assetId'] == target['video']['assetId'], label + ': target video differs from source snapshot')
        for option in options:
            sign = signs.get(option['signId'])
            if not sign:
                continue
            if item['type'] == 'WORD_TO_VIDEO':
                check(option['video']['assetId'] == sign['video']['assetId'], label + ': option video differs from source snapshot')
            else:
                check(option['label'] == sign['name'], label + ': option label differs from source snapshot')
            if item['type'] == 'VIDEO_TO_IMAGE':
                check((sign['id'], option['image']['assetId']) in images, label + ': image lacks source mapping')

    for path in ['/api/v1/practice/availability', '/api/v1/practice/sets']:
        for name, value in examples(path, 'post', 'request').items():
            source_signs(value['completedLessons'], path + '/' + name)
    sets = examples('/api/v1/practice/sets', 'post')
    for name, value in sets.items():
        label = 'Practice/' + name
        signs, images = source_signs(value['sourceLessons'], label)
        check(len({s['lessonId'] for s in value['sourceLessons']}) >= 3, label + ': fewer than three distinct lessons')
        for item in value['items']:
            if value['mode'] == 'QUIZ':
                check_quiz(item, signs, images, label)
            elif value['mode'] == 'FLASHCARDS':
                check(item['sign']['id'] in signs, label + ': flashcard contains unlearned sign')
            else:
                check({t['signId'] for t in item['left']} <= signs.keys(), label + ': matching contains unlearned sign')
    for name, value in examples('/api/v1/reports', 'post', 'request').items():
        if value['targetType'] == 'PRACTICE_ITEM':
            context = value['practiceContext']
            label = 'Report/' + name
            check(value['targetId'] == context['item']['id'], label + ': targetId differs from snapshot item')
            signs, images = source_signs(context['sourceLessons'], label)
            check_quiz(context['item'], signs, images, label)
            check(any(s['setId'] == context['setId'] and context['item'] in s['items'] for s in sets.values()),
                  label + ': report fixture does not match a generated fixture')
    return checks, errors
