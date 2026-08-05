#!/usr/bin/env python3
# PJM: генератор «натяжки» гусениц для SBM-техники.
#
# Строит замкнутый контур гусеницы по каткам модели (внешние касательные между
# окружностями + дуги) и печатает готовый Kotlin-блок таблиц TRACK_T/Y/Z/ROT
# для рендерера на SbmVehicleRenderer (+ значения getTrackDistance и
# getTrackAnimationLength). Понимает модели с развёрнутым root ([0,180,0]).
#
# Использование (пример — M10 Booker):
#   python3 track_path_gen.py src/main/resources/assets/superbwarfare/models/bedrock/vehicle/m_10_booker.geo.json \
#       --idler wheelL0:5.11 --sprocket wheelL7:4.78 \
#       --road wheelL1,wheelL2,wheelL3,wheelL4,wheelL5,wheelL6:7.33 \
#       --rollers small_wheelL0,small_wheelL1,small_wheelL2:2.62 \
#       --rest trackMovL0 --links 91
#
# Радиус указывается после «:» (уже с учётом полтолщины звена!). Если не указан —
# берётся половина Y-габарита кубов кости + половина толщины звена (--link-thickness).
# Ленивец — колесо, вокруг которого гусеница оборачивается спереди; звёздочка — сзади.
# rest — кость звена (trackMov*), её пивот должен лежать на контуре (обычно верх звёздочки
# или ленивца — где моделлер оставил стопку звеньев).

import argparse
import json
import math
import sys

STEP = 12  # шаг дуги, °


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument('geo')
    p.add_argument('--idler', required=True, help='кость[:радиус] переднего ленивца')
    p.add_argument('--sprocket', required=True, help='кость[:радиус] задней звёздочки')
    p.add_argument('--road', required=True, help='кости опорных катков через запятую[:радиус]')
    p.add_argument('--rollers', default='', help='кости поддерживающих роликов[:радиус]')
    p.add_argument('--rest', required=True, help='кость звена (trackMov*)')
    p.add_argument('--links', type=int, required=True, help='число звеньев на борт')
    p.add_argument('--link-thickness', type=float, default=1.2)
    return p.parse_args()


def main():
    args = parse_args()
    geo = json.load(open(args.geo))['minecraft:geometry'][0]
    bones = {b['name']: b for b in geo['bones']}

    root = bones.get('root', {})
    rot = root.get('rotation')
    if rot and [round(v) for v in rot] == [0, 180, 0]:
        cz = root['pivot'][2]
        flip = True   # нормализуем: перёд = -Z
        nz = lambda z: 2 * cz - z
    elif rot:
        sys.exit(f'у root нестандартный rotation {rot}')
    else:
        flip = False
        nz = lambda z: z

    half_link = args.link_thickness / 2

    def circle(spec):
        """'кость[:радиус]' -> (y, z_норм, r) по пивоту кости."""
        name, _, r = spec.partition(':')
        b = bones.get(name) or sys.exit(f'кость {name} не найдена')
        if r:
            r = float(r)
        else:
            ys = [v for c in b.get('cubes', []) for v in (c['origin'][1], c['origin'][1] + c['size'][1])]
            if not ys:
                sys.exit(f'у {name} нет кубов — укажи радиус явно: {name}:R')
            r = (max(ys) - min(ys)) / 2 + half_link
            print(f'// {name}: радиус авто = {r:.2f}', file=sys.stderr)
        return (b['pivot'][1], nz(b['pivot'][2]), r)

    idler = circle(args.idler)      # перёд (меньший z в норм. системе)
    sprocket = circle(args.sprocket)
    road = [circle(s if ':' not in args.road else s + ':' + args.road.rsplit(':', 1)[1])
            for s in args.road.rsplit(':', 1)[0].split(',')]
    rollers = []
    if args.rollers:
        rollers = [circle(s if ':' not in args.rollers else s + ':' + args.rollers.rsplit(':', 1)[1])
                   for s in args.rollers.rsplit(':', 1)[0].split(',')]

    rest_b = bones.get(args.rest) or sys.exit(f'кость {args.rest} не найдена')
    P0 = (rest_b['pivot'][1], nz(rest_b['pivot'][2]))

    if idler[1] > sprocket[1]:
        sys.exit('ленивец должен быть спереди (меньший норм. Z), звёздочка сзади — проверь --idler/--sprocket')

    road = sorted(road, key=lambda c: c[1])           # спереди назад
    rollers = sorted(rollers, key=lambda c: c[1])
    road_front, road_rear = road[0], road[-1]
    y_bottom = road_front[0] - road_front[2]

    centroid = ((idler[0] + sprocket[0] + road_front[0] + road_rear[0]) / 4,
                (idler[1] + sprocket[1] + road_front[1] + road_rear[1]) / 4)

    def outer_tangent(c1, c2):
        dy, dz = c2[0] - c1[0], c2[1] - c1[1]
        L = math.hypot(dy, dz)
        u = (dy / L, dz / L)
        v = (-u[1], u[0])
        a = (c1[2] - c2[2]) / L
        b = math.sqrt(max(0.0, 1 - a * a))
        best = None
        for sb in (b, -b):
            n = (a * u[0] + sb * v[0], a * u[1] + sb * v[1])
            mid = ((c1[0] + c2[0]) / 2 + n[0], (c1[1] + c2[1]) / 2 + n[1])
            away = (mid[0] - centroid[0]) * n[0] + (mid[1] - centroid[1]) * n[1]
            if best is None or away > best[0]:
                best = (away, n)
        n = best[1]
        return ((c1[0] + c1[2] * n[0], c1[1] + c1[2] * n[1]),
                (c2[0] + c2[2] * n[0], c2[1] + c2[2] * n[1]))

    def ang_of(c, p):
        return math.degrees(math.atan2(p[0] - c[0], p[1] - c[1])) % 360

    def arc(c, a_from, a_to):
        """дуга по убыванию угла (0=+z=корма, 90=верх, 270=низ) — направление обхода
        rest -> корма -> низ -> перёд -> верх в системе «перёд = -Z»; без концов"""
        while a_to > a_from:
            a_to -= 360
        out, a = [], a_from - STEP
        while a > a_to:
            r = math.radians(a)
            out.append((c[0] + c[2] * math.sin(r), c[1] + c[2] * math.cos(r)))
            a -= STEP
        return out

    # обход в нормализованной системе (перёд = -Z), конвенция M1A2:
    # rest -> вниз вокруг звёздочки (корма, +Z) -> нижняя ветка вперёд -> ленивец -> верх назад
    pts = [P0]
    p_s, p_wr = outer_tangent(sprocket, road_rear)
    pts += arc(sprocket, ang_of(sprocket, P0), ang_of(sprocket, p_s))
    pts += [p_s, p_wr]
    pts += arc(road_rear, ang_of(road_rear, p_wr), 270)
    pts.append((y_bottom, road_rear[1]))
    pts.append((y_bottom, road_front[1]))
    p_wf, p_i = outer_tangent(road_front, idler)
    pts += arc(road_front, 270, ang_of(road_front, p_wf))
    pts += [p_wf, p_i]
    if rollers:
        p_i2, p_r = outer_tangent(idler, rollers[0])
        pts += arc(idler, ang_of(idler, p_i), ang_of(idler, p_i2))
        pts += [p_i2, p_r]
        for c in rollers[1:]:
            pts.append((c[0] + c[2], c[1]))
    else:
        p_i2, p_sp_top = outer_tangent(idler, sprocket)
        pts += arc(idler, ang_of(idler, p_i), ang_of(idler, p_i2))
        pts += [p_i2, p_sp_top]
    pts.append(P0)

    ts, seg_angles, prev = [0.0], [], None
    for i in range(1, len(pts)):
        (y1, z1), (y2, z2) = pts[i - 1], pts[i]
        ts.append(ts[-1] + math.hypot(y2 - y1, z2 - z1))
        a = math.degrees(math.atan2(y2 - y1, z2 - z1))
        if prev is not None:
            while a - prev > 180:
                a -= 360
            while a - prev < -180:
                a += 360
        seg_angles.append(a)
        prev = a
    rots = [seg_angles[0]]
    for i in range(1, len(seg_angles)):
        rots.append((seg_angles[i - 1] + seg_angles[i]) / 2)
    rots.append(rots[0] - 360 if seg_angles[-1] < seg_angles[0] else rots[0] + 360)

    # смещения обратно в сырые координаты модели; для root-180 Z и ROT меняют знак
    sy = 1.0
    sz = -1.0 if flip else 1.0
    srot = -1.0 if flip else 1.0
    L = ts[-1]

    def karr(vals):
        chunks = [', '.join(f'{v:.3f}f' for v in vals[i:i + 10]) for i in range(0, len(vals), 10)]
        return ',\n            '.join(chunks)

    print(f'// контур {L:.2f} юнитов, {len(pts)} точек; сгенерировано pjm-tools/track_path_gen.py')
    print(f'override fun getTrackDistance() = {L / args.links:.4f}f\n')
    print(f'// в entity: override fun getTrackAnimationLength() = {round(L)}\n')
    for name, vals in (('TRACK_T', ts),
                       ('TRACK_Y', [sy * (p[0] - P0[0]) for p in pts]),
                       ('TRACK_Z', [sz * (p[1] - P0[1]) for p in pts]),
                       ('TRACK_ROT', [srot * r for r in rots])):
        print(f'private val {name} = floatArrayOf(\n            {karr(vals)}\n)')


if __name__ == '__main__':
    main()
