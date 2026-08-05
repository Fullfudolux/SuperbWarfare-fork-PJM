// PJM: Blockbench-плагин для разметки хитбоксов (OBB) и точек (камеры, ShootPos)
// техники SuperbWarfare прямо в редакторе.
//
// Установка: Blockbench -> File -> Plugins -> (⋮) Load Plugin from File -> этот файл.
//
// Использование:
//   1. Рисуй кубы-хитбоксы поверх модели (можно вращать; пивот куба держи в центре).
//   2. Выдели кубы -> Tools -> "SBW: назначить хитбокс" -> выбери тип в диалоге.
//   3. Tools -> "SBW: экспорт OBB" -> секция "OBB": [...] в буфере обмена.
//   4. Точки: выдели куб-маркер -> Tools -> "SBW: координаты точки".
//
// Все координаты берутся из фактической 3D-сцены (matrixWorld), поэтому развёрнутый
// root, вложенные группы и повороты кубов учитываются автоматически: что видишь в
// редакторе — то и получишь в игре.

(function () {
    'use strict';

    const PARTS = ['Collision', 'WheelLeft', 'WheelRight', 'MainEngine', 'SubEngine',
        'Turret', 'Body', 'Interactive', 'Empty'];

    // Визуальный подъём модели в рендерере, в блоках (M10 Booker: poseStack.translate
    // на 2/16 в M10BookerRenderer, т.к. низ гусениц в модели ниже y=0).
    // Для машин без подъёма поставь 0. На относительные координаты (отн. башни) не влияет.
    // По умолчанию 0 — подъём есть только у моделей, чей низ уходит ниже y=0 (M10 Booker: 0.125).
    const Y_OFFSET = 0;

    function findGroup(name) {
        return Group.all.find(g => g.name === name);
    }

    // сцена Blockbench -> data-пространство SBW (блоки; X влево, Y вверх, Z вперёд)
    function toData(v) {
        return [-v.x / 16, v.y / 16 + Y_OFFSET, -v.z / 16];
    }

    // мировые позиции 8 вершин куба (учитывают повороты куба и всех родительских групп)
    function worldCorners(cube) {
        const o = cube.origin;
        const out = [];
        for (const x of [cube.from[0], cube.to[0]])
            for (const y of [cube.from[1], cube.to[1]])
                for (const z of [cube.from[2], cube.to[2]]) {
                    out.push(new THREE.Vector3(x - o[0], y - o[1], z - o[2])
                        .applyMatrix4(cube.mesh.matrixWorld));
                }
        return out; // порядок: 000,001,010,011,100,101,110,111
    }

    function groupWorldPos(g) {
        return g.mesh.getWorldPosition(new THREE.Vector3());
    }

    function cubeCenterData(cube) {
        const c = worldCorners(cube);
        const avg = new THREE.Vector3();
        c.forEach(v => avg.add(v));
        return toData(avg.multiplyScalar(1 / 8));
    }

    function round(v) {
        return Math.round(v * 1000) / 1000;
    }

    function fmt(v) {
        return '[' + v.map(round).join(', ') + ']';
    }

    function copy(text, msg) {
        navigator.clipboard.writeText(text);
        Blockbench.showQuickMessage(msg, 2000);
        console.log(text);
    }

    function cubeToObb(cube, turretOriginData) {
        const c = worldCorners(cube);
        // рёбра бокса вдоль локальных осей X/Y/Z
        const ex = c[4].clone().sub(c[0]);
        const ey = c[2].clone().sub(c[0]);
        const ez = c[1].clone().sub(c[0]);
        const size = [ex.length() / 32, ey.length() / 32, ez.length() / 32];

        let pos = cubeCenterData(cube);
        if (turretOriginData) pos = pos.map((v, i) => v - turretOriginData[i]);

        // оси бокса в data-пространстве (зеркало X и Z, как в toData)
        const md = v => new THREE.Vector3(-v.x, v.y, -v.z).normalize();
        const basis = new THREE.Matrix4().makeBasis(md(ex), md(ey), md(ez));
        // игра применяет CustomRotate в порядке Y -> X -> Z
        const e = new THREE.Euler().setFromRotationMatrix(basis, 'YXZ');
        const deg = [e.x, e.y, e.z].map(a => a * 180 / Math.PI);
        const rotated = deg.some(a => Math.abs(a) > 0.05);

        const partName = cube.name.replace(/\d+$/, '');
        const lines = [];
        if (PARTS.includes(partName) && partName !== 'Empty') {
            lines.push(`      "Part": "${partName}"`);
        }
        lines.push(`      "Size": ${fmt(size)}`);
        lines.push(`      "Position": ${fmt(pos)}`);
        if (rotated) {
            lines.push(`      "CustomRotate": ${fmt(deg)}`);
        }
        if (turretOriginData) {
            lines.push('      "Transform": "Turret"');
            lines.push('      "Rotation": "Turret"');
        }
        return '    {\n' + lines.join(',\n') + '\n    }';
    }

    function exportObb() {
        const entries = [];

        const obb = findGroup('obb');
        if (obb) {
            obb.children.forEach(c => {
                if (c instanceof Cube) entries.push(cubeToObb(c, null));
            });
        }
        const obbTurret = findGroup('obb_turret');
        if (obbTurret) {
            const turret = findGroup('turret');
            if (!turret) {
                Blockbench.showQuickMessage('Группа turret не найдена — нужна для obb_turret', 3000);
                return;
            }
            const to = toData(groupWorldPos(turret));
            obbTurret.children.forEach(c => {
                if (c instanceof Cube) entries.push(cubeToObb(c, to));
            });
        }

        if (!entries.length) {
            Blockbench.showQuickMessage('Нет кубов в группах obb / obb_turret', 3000);
            return;
        }
        copy('"OBB": [\n' + entries.join(',\n') + '\n  ],',
            `OBB: ${entries.length} боксов скопировано`);
    }

    function exportPoint() {
        const cube = Cube.selected[0];
        if (!cube) {
            Blockbench.showQuickMessage('Выдели куб-маркер', 3000);
            return;
        }
        const p = cubeCenterData(cube);

        const lines = [`Vehicle (абсолютно): ${fmt(p)}`];
        for (const bone of ['turret', 'barrel', 'passengerWeaponStationYaw']) {
            const g = findGroup(bone);
            if (g) {
                const o = toData(groupWorldPos(g));
                lines.push(`отн. ${bone}: ${fmt(p.map((v, i) => v - o[i]))}`);
            }
        }
        copy(lines.join('\n'), 'Координаты точки скопированы');
    }

    function getOrCreateGroup(name) {
        let g = findGroup(name);
        if (!g) {
            g = new Group({ name: name, export: false }).init();
        }
        g.export = false; // хитбоксы не должны попадать в geo.json
        return g;
    }

    function assignDialog() {
        const cubes = Cube.selected.slice();
        if (!cubes.length) {
            Blockbench.showQuickMessage('Сначала выдели кубы-хитбоксы', 3000);
            return;
        }
        new Dialog({
            id: 'sbw_assign_obb',
            title: `SBW: назначить хитбокс (${cubes.length} куб.)`,
            form: {
                part: {
                    label: 'Тип модуля',
                    type: 'select',
                    options: {
                        track: 'Гусеница — авто лево/право',
                        Collision: 'Collision — физика корпуса (низ на землю!)',
                        MainEngine: 'MainEngine — двигатель',
                        SubEngine: 'SubEngine — второй двигатель',
                        Turret: 'Turret — башня (модуль)',
                        Body: 'Body — корпус, просто урон',
                        Interactive: 'Interactive — зона ПКМ',
                        Empty: 'Empty — просто хитбокс',
                        WheelLeft: 'WheelLeft — левая гусеница (вручную)',
                        WheelRight: 'WheelRight — правая гусеница (вручную)'
                    }
                },
                turret: {
                    label: 'Бокс башни (крутится с башней)',
                    type: 'checkbox',
                    value: false
                }
            },
            onConfirm(result) {
                const group = getOrCreateGroup(result.turret ? 'obb_turret' : 'obb');
                try { Undo.initEdit({ elements: cubes, outliner: true }); } catch (e) { }
                cubes.forEach(cube => {
                    let part = result.part;
                    if (part === 'track') {
                        part = cubeCenterData(cube)[0] >= 0 ? 'WheelLeft' : 'WheelRight';
                    }
                    cube.name = part;
                    cube.addTo(group);
                });
                try { Undo.finishEdit('SBW: назначить хитбокс'); } catch (e) { }
                Canvas.updateAll();
                Blockbench.showQuickMessage(
                    `Назначено: ${cubes.length} → группа ${group.name}`, 2500);
            }
        }).show();
    }

    let actionObb, actionPoint, actionAssign;

    Plugin.register('sbw_vehicle_tools', {
        title: 'SBW Vehicle Tools (PJM)',
        author: 'PJM',
        description: 'Разметка OBB-хитбоксов и точек (камеры, ShootPos) для техники SuperbWarfare',
        icon: 'construction',
        version: '2.0.0',
        variant: 'both',
        onload() {
            actionObb = new Action('sbw_export_obb', {
                name: 'SBW: экспорт OBB',
                icon: 'select_all',
                click: exportObb
            });
            actionPoint = new Action('sbw_export_point', {
                name: 'SBW: координаты точки',
                icon: 'my_location',
                click: exportPoint
            });
            actionAssign = new Action('sbw_assign_obb', {
                name: 'SBW: назначить хитбокс',
                icon: 'label',
                click: assignDialog
            });
            MenuBar.addAction(actionAssign, 'tools');
            MenuBar.addAction(actionObb, 'tools');
            MenuBar.addAction(actionPoint, 'tools');
        },
        onunload() {
            actionObb.delete();
            actionPoint.delete();
            actionAssign.delete();
        }
    });
})();
