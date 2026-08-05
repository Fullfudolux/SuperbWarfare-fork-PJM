#!/usr/bin/env python3
# PJM: калькулятор координат Blockbench -> data-JSON техники (сиденья, камеры,
# ShootPos, OBB-хитбоксы). Понимает модели с развёрнутым root ([0, 180, 0]).
#
# Использование:
#   python3 vehicle_coords.py <geo.json> info
#       — пивоты ключевых костей и габариты модели в data-координатах
#   python3 vehicle_coords.py <geo.json> point X Y Z [--rel КОСТЬ]
#       — точка Blockbench -> data-координаты; --rel turret / barrel /
#         passengerWeaponStationYaw / любая кость — даст смещение от её пивота
#         (для сидений/камер с "Transform": "Turret", ShootPos с "Transform": "Barrel" и т.п.)
#   python3 vehicle_coords.py <geo.json> obb X1 Y1 Z1 X2 Y2 Z2
#       — два противоположных угла бокса в Blockbench -> "Size" и "Position" для OBB
#
# Конвенция data-пространства SBW: X — влево, Y — вверх, Z — вперёд, в блоках (юниты/16).

import json
import os
import sys


def take_path(args):
    """Возвращает (путь, остальные аргументы); склеивает путь с пробелами без кавычек."""
    for i in range(1, len(args)):
        cand = ' '.join(args[1:i + 1])
        if os.path.isfile(cand):
            return cand, args[i + 1:]
    sys.exit(f'файл не найден: {args[1] if len(args) > 1 else "<нет>"} '
             f'(путь с пробелами бери в кавычки)')


def load(path):
    geo = json.load(open(path))['minecraft:geometry'][0]
    bones = {b['name']: b for b in geo['bones']}
    root = bones.get('root')
    rot = root.get('rotation') if root else None
    if rot and [round(v) for v in rot] == [0, 180, 0]:
        cx, cz = root['pivot'][0], root['pivot'][2]
        # модель развёрнута root'ом: приводим к стандартной (перёд = -Z)
        to_std = lambda p: (2 * cx - p[0], p[1], 2 * cz - p[2])
    elif rot:
        sys.exit(f'у root нестандартный rotation {rot} — поддержаны только [0,180,0] или его отсутствие')
    else:
        to_std = lambda p: (p[0], p[1], p[2])
    return geo, bones, to_std


Y_OFFSET = 0.0  # визуальный подъём модели в рендерере; для M10 Booker: --y-offset 0.125


def to_data(p, to_std):
    x, y, z = to_std(p)
    return (x / 16, y / 16 + Y_OFFSET, -z / 16)


def fmt(v):
    return f'[{v[0]:.4g}, {v[1]:.4g}, {v[2]:.4g}]'


def cube_corners(c):
    # повороты кубов не учитываются — для габаритов хватает AABB
    import itertools
    o, s = c['origin'], c['size']
    return [(o[0] + a * s[0], o[1] + b * s[1], o[2] + d * s[2])
            for a, b, d in itertools.product((0, 1), repeat=3)]


def main():
    global Y_OFFSET
    if '--y-offset' in sys.argv:
        i = sys.argv.index('--y-offset')
        Y_OFFSET = float(sys.argv[i + 1])
        del sys.argv[i:i + 2]
    if len(sys.argv) < 3:
        sys.exit(__doc__ or 'см. шапку файла')
    path, rest = take_path(sys.argv)
    geo, bones, to_std = load(path)
    cmd = rest[0] if rest else 'info'

    if cmd == 'info':
        for name in ('turret', 'barrel', 'passengerWeaponStation', 'passengerWeaponStationYaw',
                     'passengerWeaponStationPitch', 'smoke'):
            b = bones.get(name)
            if b:
                print(f'{name}: data {fmt(to_data(b["pivot"], to_std))}')
        t = bones.get('turret')
        for name in ('barrel', 'passengerWeaponStationYaw'):
            b = bones.get(name)
            if b and t:
                d = tuple(a - c for a, c in zip(to_data(b['pivot'], to_std), to_data(t['pivot'], to_std)))
                print(f'{name} отн. turret: {fmt(d)}')
        xs, ys, zs = [], [], []
        for b in geo['bones']:
            for c in b.get('cubes', []):
                for p in cube_corners(c):
                    q = to_data(p, to_std)
                    xs.append(q[0]); ys.append(q[1]); zs.append(q[2])
        print(f'габариты модели, data: X {min(xs):.3f}..{max(xs):.3f}  '
              f'Y {min(ys):.3f}..{max(ys):.3f}  Z {min(zs):.3f}..{max(zs):.3f} (Z+ = перёд)')
        return

    if cmd == 'point':
        p = tuple(float(v) for v in rest[1:4])
        d = to_data(p, to_std)
        if '--rel' in rest:
            bone = rest[rest.index('--rel') + 1]
            if bone not in bones:
                sys.exit(f'кость {bone} не найдена')
            ref = to_data(bones[bone]['pivot'], to_std)
            d = tuple(a - b for a, b in zip(d, ref))
            print(f'отн. {bone}: {fmt(d)}')
        else:
            print(f'data: {fmt(d)}')
        return

    if cmd == 'obb':
        p1 = to_data(tuple(float(v) for v in rest[1:4]), to_std)
        p2 = to_data(tuple(float(v) for v in rest[4:7]), to_std)
        size = tuple(abs(a - b) / 2 for a, b in zip(p1, p2))
        pos = tuple((a + b) / 2 for a, b in zip(p1, p2))
        print(f'"Size": {fmt(size)},\n"Position": {fmt(pos)}')
        return

    sys.exit(f'неизвестная команда {cmd} (info / point / obb)')


if __name__ == '__main__':
    main()
