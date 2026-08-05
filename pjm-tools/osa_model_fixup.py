#!/usr/bin/env python3
# PJM: привязка модели «Оса-АКМ» к уже посчитанным data-координатам (OBB, камеры, ShootPos).
#
# Зачем: модель собрана «носом по +X» и разворачивается на нос-по-N через rotation root [0,90,0].
# При каждом ре-экспорте из Blockbench геометрия уезжает на произвольный сдвиг, и машина
# перестаёт совпадать с хитбоксами. Скрипт возвращает её на место, решая пивот root из
# опорной точки, а не подгоняя число руками. Запускать после КАЖДОЙ замены osa.geo.json.
#
#   python3 pjm-tools/osa_model_fixup.py
#
# Что делает (идемпотентно):
#   1. Переименовывает кости под конвенцию SBW (Turret->turret, колёса -> wheel[LR]n[Turn]).
#   2. Ставит пивоты колёс в центр их кубов (в исходной модели они заданы неверно).
#   3. Подбирает пивот root так, чтобы пивот башни встал ровно в TURRET_POS_DATA.

import json

GEO = 'src/main/resources/assets/superbwarfare/models/bedrock/vehicle/osa.geo.json'

RENAME = {'Turret': 'turret',
          'frontL': 'wheelL0Turn', 'frontR': 'wheelR0Turn',
          'centerL': 'wheelL1', 'centerR': 'wheelR1',
          'backL': 'wheelL2', 'backR': 'wheelR2'}

# Куда должен встать пивот башни в data-пространстве SBW (X влево, Y вверх, Z вперёд).
# Это же значение лежит в "TurretPos" в data/superbwarfare/sbw/vehicles/osa.json —
# относительно него посчитаны все боксы башни, камера наводчика и точки пуска.
TURRET_POS_DATA = (-0.001, 2.248, 0.304)

# Разворот модели вокруг Y на -90 дег в data-пространстве (root rotation [0,90,0]).
ROT = lambda v: (-v[2], v[1], v[0])


def to_data(geo):
    return (geo[0] / 16, geo[1] / 16, -geo[2] / 16)


def main():
    d = json.load(open(GEO))
    g = d['minecraft:geometry'][0]
    bones = {}

    for b in g['bones']:
        b['name'] = RENAME.get(b['name'], b['name'])
        if b.get('parent') in RENAME:
            b['parent'] = RENAME[b['parent']]
        if b['name'].startswith('wheel'):
            e = [[1e9, -1e9] for _ in range(3)]
            for c in b['cubes']:
                for i in range(3):
                    e[i][0] = min(e[i][0], c['origin'][i])
                    e[i][1] = max(e[i][1], c['origin'][i] + c['size'][i])
            b['pivot'] = [round((lo + hi) / 2, 4) for lo, hi in e]
        bones[b['name']] = b

    root, turret = bones['root'], bones['turret']
    root['rotation'] = [0, 90, 0]

    # data(g) = ROT(g_data) + off, где off = P_data - ROT(P_data).
    # Нужно off = TURRET_POS_DATA - ROT(turret_pivot_data); при ROT = поворот на -90:
    #   off = (px + pz, 0, pz - px)  =>  px = (off.x - off.z) / 2, pz = (off.x + off.z) / 2
    rt = ROT(to_data(turret['pivot']))
    off = [TURRET_POS_DATA[i] - rt[i] for i in range(3)]
    px = (off[0] - off[2]) / 2
    pz = (off[0] + off[2]) / 2
    root['pivot'] = [round(px * 16, 4), root['pivot'][1], round(-pz * 16, 4)]

    # SbmVehicleRenderer при отдаче делает base.x = ... / base.z = ... (присваиванием),
    # то есть обнуляет собственное смещение кости. Это безопасно, только если пивот base
    # совпадает с пивотом root — иначе корпус уезжает на их разницу. Уравниваем.
    bones['base']['pivot'] = list(root['pivot'])

    json.dump(d, open(GEO, 'w'), ensure_ascii=False, indent=2)

    # контроль: пересчёт нескольких костей в data-координаты
    P = to_data(root['pivot'])
    real_off = [P[i] - ROT(P)[i] for i in range(3)]
    def data(geo):
        r = ROT(to_data(geo))
        return [round(r[i] + real_off[i], 3) for i in range(3)]

    print('root pivot ->', root['pivot'])
    print('TurretPos  ->', data(turret['pivot']), ' (цель', list(TURRET_POS_DATA), ')')
    for n in ('wheelL0Turn', 'wheelL1', 'wheelL2'):
        print(n.ljust(12), '->', data(bones[n]['pivot']))


if __name__ == '__main__':
    main()
