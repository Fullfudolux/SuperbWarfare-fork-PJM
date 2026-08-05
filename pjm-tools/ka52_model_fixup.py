#!/usr/bin/env python3
# PJM: приведение модели Ка-52 «Аллигатор» к конвенции SBW. Запускать после КАЖДОЙ
# замены ka_52.geo.json из Blockbench.
#
#   python3 pjm-tools/ka52_model_fixup.py
#
# Зачем: нижняя грань ванильного AABB сущности совпадает с началом координат модели
# (y = 0). У Ка-52 стойки шасси смоделированы ниже нуля, поэтому машина садится корпусом,
# а шасси уходит в грунт. Апстримовые модели этого не допускают: у Ми-28 низ на −0.031 бл,
# у Т-90А на −0.015. Скрипт поднимает модель ровно на столько, чтобы низ встал на 0.
#
# Что делает (идемпотентно — повторный запуск ничего не меняет):
#   1. identifier -> geometry.ka_52
#   2. Пивот прицельного контейнера `pricel` -> центр его куба (в модели он зеркальный по Z,
#      вращать вокруг него нельзя — см. Ka52Renderer).
#   3. Поднимает всю геометрию так, чтобы самая нижняя точка отрисовки легла на y = 0.
#
# ВНИМАНИЕ: п.3 меняет положение модели относительно data-координат. Если скрипт впервые
# поднимает модель на N единиц, все Y в data/superbwarfare/sbw/vehicles/ka_52.json
# (OBB, кресла, камеры, TurretPos, ShootPos в системе Vehicle, TerrainCompat,
# RotateOffsetHeight) должны уехать на те же N/16 блока. При обычном ре-экспорте той же
# модели сдвиг выходит нулевым и трогать data-JSON не нужно.

import itertools
import json
import math

GEO = 'src/main/resources/assets/superbwarfare/models/bedrock/vehicle/ka_52.geo.json'


def rot_matrix(rx, ry, rz):
    """Матрица поворота кости/куба в отрисованном виде.

    Экспорт Blockbench зеркалит модель по X, поэтому углы вокруг X и Y приходят
    с обратным знаком. Проверка: у `land_F` только при инверсии стойка оказывается
    ВЫШЕ колеса (y 4.34 против 0.55), при исходном знаке — ниже.
    """
    rx, ry, rz = -math.radians(rx), -math.radians(ry), math.radians(rz)
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    mx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    my = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    mz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    mul = lambda a, b: [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    return mul(mx, mul(my, mz))


def apply(m, p, pivot):
    d = [p[i] - pivot[i] for i in range(3)]
    return [pivot[i] + sum(m[i][k] * d[k] for k in range(3)) for i in range(3)]


def lowest_point(geometry):
    bones = {b['name']: b for b in geometry['bones']}

    def parents(name):
        out, bone = [], bones.get(name)
        while bone:
            out.append(bone)
            bone = bones.get(bone.get('parent'))
        return out

    low = None
    for bone in geometry['bones']:
        for cube in bone.get('cubes', []):
            o, s = cube['origin'], cube['size']
            corners = [[o[0] + dx * s[0], o[1] + dy * s[1], o[2] + dz * s[2]]
                       for dx, dy, dz in itertools.product((0, 1), repeat=3)]
            if cube.get('rotation'):
                m = rot_matrix(*cube['rotation'])
                pivot = cube.get('pivot') or o
                corners = [apply(m, p, pivot) for p in corners]
            for parent in parents(bone['name']):
                if parent.get('rotation'):
                    m = rot_matrix(*parent['rotation'])
                    corners = [apply(m, p, parent['pivot']) for p in corners]
            y = min(p[1] for p in corners)
            if low is None or y < low:
                low = y
    return low


def main():
    data = json.load(open(GEO))
    geometry = data['minecraft:geometry'][0]
    bones = {b['name']: b for b in geometry['bones']}

    geometry['description']['identifier'] = 'geometry.ka_52'

    pricel = bones.get('pricel')
    if pricel and pricel.get('cubes'):
        o, s = pricel['cubes'][0]['origin'], pricel['cubes'][0]['size']
        pricel['pivot'] = [round(o[i] + s[i] / 2, 5) for i in range(3)]

    delta = -lowest_point(geometry)
    if abs(delta) > 1e-6:
        for bone in geometry['bones']:
            bone['pivot'][1] = round(bone['pivot'][1] + delta, 5)
            for cube in bone.get('cubes', []):
                cube['origin'][1] = round(cube['origin'][1] + delta, 5)
                if cube.get('pivot'):
                    cube['pivot'][1] = round(cube['pivot'][1] + delta, 5)

    json.dump(data, open(GEO, 'w'), separators=(',', ':'))
    print(f'подъём модели: {delta:+.4f} ед = {delta / 16:+.6f} бл')
    print(f'низ после правки: {lowest_point(geometry):+.4f} ед')


if __name__ == '__main__':
    main()
