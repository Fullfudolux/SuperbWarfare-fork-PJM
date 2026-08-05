#!/usr/bin/env python3
# PJM: привязка модели БМПТ-72 «Терминатор» к движку SBM (аналог osa_model_fixup.py).
#
# Зачем: при экспорте из Blockbench у модели (а) пивоты катков заданы неверно (не в центре
# кубов), (б) нет костей звеньев гусеницы (trackMov*/trackRot*), которые рендерер «растаскивает»
# по контуру — есть только одно звено `group9`. Скрипт нормализует модель под конвенцию SBW:
#   1. пивоты катков -> в центр их кубов;
#   2. переименование катков в wheel[LR]<n> (перёд->корма) и роликов в roller[LR]<n>;
#   3. генерация trackL/trackR + trackMov[LR]<n>/trackRot[LR]<n> (плоское звено как у Ajax),
#      все с одним пивотом на контуре (верх переднего ленивца) — рендерер их распределит;
#   4. удаление служебного `track`/`group9`;
#   5. разбиение единой кости ПТУР `raketi` (4 куба) на raketa0..3 — рендерер прячет
#      отстрелянную ракету по индексу пуска.
# Идемпотентно. ЗАПУСКАТЬ ПОСЛЕ КАЖДОГО ре-экспорта terminator.geo.json.
#   python3 pjm-tools/terminator_model_fixup.py
# После него запустить track_path_gen.py (см. вывод) и вставить таблицы в TerminatorRenderer.

import json

GEO = 'src/main/resources/assets/superbwarfare/models/bedrock/vehicle/terminator.geo.json'

LINKS = 41          # звеньев на борт (contour ~204 юнита / ~5)
LINK_DEPTH = 5.0    # длина звена вдоль гусеницы (z)
LINK_WIDTH = 9.0    # ширина звена (x)
HALF_LINK = 0.6

# оригинальное имя -> новое (перёд = -Z, т.е. по возрастанию z: ленивец..катки..звёздочка)
RENAME = {
    'wheels_L': 'wheelsL', 'wheels_R': 'wheelsR',
    'wheel8': 'wheelL0', 'wheel19': 'wheelL1', 'wheel18': 'wheelL2', 'wheel5': 'wheelL3',
    'wheel20': 'wheelL4', 'wheel21': 'wheelL5', 'wheel22': 'wheelL6', 'wheel6': 'wheelL7',
    'group5': 'rollerL0', 'group6': 'rollerL1', 'group7': 'rollerL2',
    'wheel3': 'wheelR0', 'wheel7': 'wheelR1', 'wheel4': 'wheelR2', 'wheel12': 'wheelR3',
    'wheel9': 'wheelR4', 'wheel10': 'wheelR5', 'wheel11': 'wheelR6', 'wheel2': 'wheelR7',
    'group13': 'rollerR0', 'group12': 'rollerR1', 'group11': 'rollerR2',
}

# UV-регионы одного звена (взяты из служебного group9): протектор up/down 9x5, торцы, борта.
LINK_UV = {
    'north': {'uv': [253, 45], 'uv_size': [9, 1]},
    'south': {'uv': [253, 45], 'uv_size': [9, 1]},
    'east':  {'uv': [30, 295], 'uv_size': [1, 5]},
    'west':  {'uv': [32, 295], 'uv_size': [1, 5]},
    'up':    {'uv': [253, 45], 'uv_size': [9, 5]},
    'down':  {'uv': [253, 68], 'uv_size': [9, 5]},
}


def cube_center(b):
    e = [[1e9, -1e9] for _ in range(3)]
    for c in b.get('cubes', []):
        for i in range(3):
            e[i][0] = min(e[i][0], c['origin'][i])
            e[i][1] = max(e[i][1], c['origin'][i] + c['size'][i])
    return [round((lo + hi) / 2, 4) for lo, hi in e], e


def link_cube(rest):
    x, y, z = rest
    return {
        # раскладка как у Ajax: звено выходит вверх/назад от пивота, поворот 180° кладёт его
        # протектором наружу; наклон вдоль контура задаёт рендерер (trackRot)
        'origin': [x - LINK_WIDTH / 2, y, z],
        'size': [LINK_WIDTH, 1, LINK_DEPTH],
        'pivot': [x, y, z],
        'rotation': [180, 0, 0],
        'uv': {k: dict(v) for k, v in LINK_UV.items()},
    }


# соответствие индекса пуска (ammo-1) модельному origin[0] куба ПТУР (проверено в игре:
# сторона X у пуска и модели совпадает, без зеркала). raketa<i> прячется при ammo<=i.
RAKETA_INDEX_TO_ORIGIN_X = {0: 19.76537, 1: 15.76537, 2: -18.76537, 3: -22.76537}


def split_raketi(g):
    """Единую кость `raketi` (4 куба) -> raketa0..3, каждый со своим кубом. Идемпотентно."""
    bones = g['bones']
    src = next((b for b in bones if b['name'] == 'raketi'), None)
    if src is None:
        return  # уже разбито (raketa0..3) либо кости нет
    i = bones.index(src)
    parent = src.get('parent', 'turrets')
    new = []
    for idx in range(4):
        ox = RAKETA_INDEX_TO_ORIGIN_X[idx]
        cube = next(c for c in src['cubes'] if round(c['origin'][0], 2) == round(ox, 2))
        px = 15.76537 if ox > 0 else -15.76537
        new.append({'name': f'raketa{idx}', 'parent': parent,
                    'pivot': [px, 40.84776, 31.5], 'cubes': [cube]})
    bones[i:i + 1] = new


def main():
    d = json.load(open(GEO))
    g = d['minecraft:geometry'][0]

    split_raketi(g)

    # выкидываем ранее сгенерированные track-кости и служебный group9/track (идемпотентность)
    g['bones'] = [b for b in g['bones']
                  if not b['name'].startswith(('trackMov', 'trackRot', 'trackL', 'trackR'))
                  and b['name'] not in ('track', 'group9')]

    bones = {}
    for b in g['bones']:
        b['name'] = RENAME.get(b['name'], b['name'])
        if b.get('parent') in RENAME:
            b['parent'] = RENAME[b['parent']]
        if b['name'].startswith(('wheel', 'roller')) and b.get('cubes'):
            b['pivot'], _ = cube_center(b)
        bones[b['name']] = b

    # rest-пивот = верх задней звёздочки (wheelL7 / wheelR7) — так требует track_path_gen
    def rest_of(sprocket_name):
        c, e = cube_center(bones[sprocket_name])
        return [c[0], round(e[1][1] + HALF_LINK, 4), c[2]]   # x центр, y = верх куба + полтолщины, z центр

    rest_L = rest_of('wheelL7')
    rest_R = rest_of('wheelR7')

    new_bones = [
        {'name': 'trackL', 'parent': 'root', 'pivot': list(rest_L)},
        {'name': 'trackR', 'parent': 'root', 'pivot': list(rest_R)},
    ]
    for side, rest in (('L', rest_L), ('R', rest_R)):
        for n in range(LINKS):
            new_bones.append({'name': f'trackMov{side}{n}', 'parent': f'track{side}', 'pivot': list(rest)})
            new_bones.append({'name': f'trackRot{side}{n}', 'parent': f'trackMov{side}{n}',
                              'pivot': list(rest), 'cubes': [link_cube(rest)]})
    g['bones'].extend(new_bones)

    json.dump(d, open(GEO, 'w'), ensure_ascii=False, indent=2)

    print(f'rest_L={rest_L}  rest_R={rest_R}  links/side={LINKS}')
    print('готово. теперь контур:')
    print('  python3 pjm-tools/track_path_gen.py ' + GEO + ' \\')
    print('    --idler wheelL0 --sprocket wheelL7 \\')
    print('    --road wheelL1,wheelL2,wheelL3,wheelL4,wheelL5,wheelL6 \\')
    print('    --rollers rollerL0,rollerL1,rollerL2 \\')
    print(f'    --rest trackMovL0 --links {LINKS}')


if __name__ == '__main__':
    main()
