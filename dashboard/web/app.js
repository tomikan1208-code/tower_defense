/* ══════════════════════════════════════════════════
   学習ダッシュボード フロント（テンプレート）
   指標の定義は /api/config から受け取るので、
   ゲームごとにこのファイルを書き換える必要はない。
   ══════════════════════════════════════════════════ */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const state = { config: null, status: {}, history: [], range: 0, logVersion: null };

    // ── 表示ヘルパー ──
    const fmt = {
        rate: (v) => (v === null || v === undefined) ? '—' : (v * 100).toFixed(1) + '%',
        float: (v) => (v === null || v === undefined) ? '—' : Number(v).toFixed(4),
        int: (v) => (v === null || v === undefined) ? '—' : Math.round(v).toLocaleString('ja-JP'),
    };
    const show = (m, v) => (fmt[m.fmt] || fmt.float)(v);
    const esc = (s) => String(s).replace(/[&<>"']/g,
        (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

    async function getJSON(url) {
        const res = await fetch(url);
        if (!res.ok) throw new Error(url + ' → ' + res.status);
        return res.json();
    }

    async function post(url, body) {
        const res = await fetch(url, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body || {}),
        });
        return res.json();
    }

    // ── 進捗タブ ──
    function tile(label, value, unit, sub) {
        return '<div class="tile"><div class="tile-label">' + esc(label) + '</div>'
            + '<div class="tile-value">' + value
            + (unit ? '<span class="unit">' + esc(unit) + '</span>' : '') + '</div>'
            + (sub ? '<div class="tile-sub">' + sub + '</div>' : '') + '</div>';
    }

    function renderProgress() {
        const st = state.status, recs = state.history;
        const latest = recs.length ? recs[recs.length - 1] : (st.latest || null);
        // 学習中は「いま回している世代」を出す。停止すると最後に完了した世代に戻る。
        const live = st.is_running ? st.live : null;
        const doneGen = latest ? latest.episode : 0;
        const showGen = (live && live.episode) ? live.episode : doneGen;

        const tiles = [
            tile('現在の世代', showGen, '',
                (live && live.episode) ? '学習中（完了済み ' + doneGen + ' 世代）' : ''),
            live
                ? tile('進行中のステップ', live.step + ' / ' + live.max_steps, '',
                    (live.phase === 'pretest' ? '起動前チェック中' : '自己対戦中')
                    + '<span class="step-bar"><span class="step-bar-fill" style="width:'
                    + (live.step / live.max_steps * 100).toFixed(1) + '%"></span></span>')
                : tile('進行中のステップ', '—', '', st.is_running ? '学習の準備中' : '世代の合間'),
            tile('ペース', st.pace_sec ? st.pace_sec.toFixed(1) : '—',
                st.pace_sec ? '秒/世代' : '',
                st.pace_sec ? '約 ' + (60 / st.pace_sec).toFixed(1) + ' 世代/分' : '直近の実測なし'),
        ];
        // 指標のタイルは設定から自動生成する
        state.config.metrics.forEach(function (m) {
            const found = lastDefined(recs, m.key);
            tiles.push(tile(m.label, found ? show(m, found[m.key]) : '—', '',
                found ? '第 ' + found.episode + ' 世代' : '未測定'));
        });
        $('progressTiles').innerHTML = tiles.join('');

        $('progressNote').textContent = st.is_running ? (st.mode || '学習中')
            : (recs.length ? '待機中（記録済み ' + recs.length + ' 世代）' : '待機中');
        $('statusText').textContent = st.is_running ? '学習中' : '待機中';
        $('statusDot').className = 'status-dot' + (st.is_running ? ' running' : '');
        $('statusGen').textContent = showGen ? '第 ' + showGen + ' 世代' : '';
        $('startBtn').disabled = !!st.is_running;
        $('stopBtn').disabled = !st.is_running;

        $('logView').innerHTML = (st.logs || []).map(function (l) {
            return '<div class="log-line ' + esc(l.tag) + '"><span class="log-time">'
                + esc(l.time) + '</span>' + esc(l.text) + '</div>';
        }).join('');
    }

    /** その指標が最後に記録された世代を返す（評価がまばらでも拾える） */
    function lastDefined(records, key) {
        for (let i = records.length - 1; i >= 0; i--) {
            const v = records[i][key];
            if (v !== null && v !== undefined) return records[i];
        }
        return null;
    }

    function renderTable() {
        const metrics = state.config.metrics;
        $('genHead').innerHTML = '<th>世代</th>'
            + metrics.map((m) => '<th class="num">' + esc(m.label) + '</th>').join('');
        const rows = state.history.slice().reverse().slice(0, 500);
        $('genTableBody').innerHTML = rows.length
            ? rows.map(function (r) {
                return '<tr><td>' + r.episode + '</td>'
                    + metrics.map((m) => '<td class="num">' + show(m, r[m.key]) + '</td>').join('')
                    + '</tr>';
            }).join('')
            : '<tr><td colspan="' + (metrics.length + 1) + '" class="empty">'
              + 'まだ世代の記録がありません</td></tr>';
        $('genCountNote').textContent = state.history.length + ' 世代を記録中';
    }

    // ── グラフタブ ──
    function renderCharts() {
        const recs = state.range > 0 ? state.history.slice(-state.range) : state.history;
        const xMin = recs.length ? recs[0].episode : undefined;
        const xMax = recs.length ? recs[recs.length - 1].episode : undefined;

        // 指標ごとに1枚。単位の違うものを1つのグラフに混ぜない（2軸は作らない）
        $('chartCards').innerHTML = state.config.metrics.map(function (m, i) {
            return '<div class="card"><div class="card-head"><div class="card-title">'
                + esc(m.label) + '</div></div><div class="chart-box" id="chart' + i + '"></div></div>';
        }).join('');

        state.config.metrics.forEach(function (m, i) {
            const scale = m.fmt === 'rate' ? 100 : 1;
            const points = recs.map(function (r) {
                const v = r[m.key];
                return { x: r.episode, y: (v === null || v === undefined) ? null : v * scale };
            });
            const axis = m.fmt === 'rate'
                ? { label: '%', min: 0, max: 100, format: (v) => v.toFixed(0) + '%' }
                : { label: '', format: (v) => Math.abs(v) >= 1000 ? v.toFixed(0) : v.toFixed(3) };
            MDDChart.render($('chart' + i), {
                height: 240, xLabel: '世代', xMin: xMin, xMax: xMax,
                series: [{ key: m.key, label: m.label, points: points,
                           tipFormat: (v) => m.fmt === 'rate' ? v.toFixed(1) + '%' : v.toFixed(4) }],
                axes: { left: axis },
                emptyText: 'まだ記録がありません。',
            });
        });
    }

    // ── 人数別の勝率（入れ子 dict の指標） ──
    // 名前は初出順で固定する。成績順に並べ替えると、世代が進むたびに
    // グラフの並びが入れ替わって読めなくなる。
    const dictOrder = {};

    function renderDictCharts() {
        const box = $('playerCharts');
        if (!box) return;
        const key = 'win_rate_by_players';
        const recs = state.range > 0 ? state.history.slice(-state.range) : state.history;
        const names = dictOrder[key] || (dictOrder[key] = []);
        recs.forEach(function (r) {
            const d = r[key];
            if (d) Object.keys(d).forEach(function (n) {
                if (names.indexOf(n) < 0) names.push(n);
            });
        });
        if (!names.length) {
            box.innerHTML = '<div class="empty">まだ人数別の評価がありません。'
                + '評価は数世代ごとに走ります。</div>';
            return;
        }
        const xMin = recs.length ? recs[0].episode : undefined;
        const xMax = recs.length ? recs[recs.length - 1].episode : undefined;
        box.innerHTML = names.map(function (n, i) {
            return '<div class="sm-item"><div class="card-title">' + esc(n)
                + '</div><div class="chart-box" id="pc' + i + '"></div></div>';
        }).join('');
        // 同じ軸を渡して、人数どうしの高さを直接比べられるようにする
        const axis = { label: '%', min: 0, max: 100, format: (v) => v.toFixed(0) + '%' };
        names.forEach(function (n, i) {
            const points = recs.map(function (r) {
                const d = r[key];
                const v = d ? d[n] : null;
                return { x: r.episode, y: (v === null || v === undefined) ? null : v * 100 };
            });
            MDDChart.render($('pc' + i), {
                height: 200, xLabel: '世代', xMin: xMin, xMax: xMax,
                series: [{ key: n, label: n, points: points,
                           tipFormat: (v) => v.toFixed(1) + '%' }],
                axes: { left: axis },
                emptyText: 'まだ記録がありません。',
            });
        });
    }

    // ── 対局ビューア ──
    const replay = { data: null, frame: 0 };

    function renderReplay() {
        const d = replay.data;
        if (!d) return;
        $('replayLegend').textContent = d.legend;
        $('replayNote').textContent = d.source + ' / ' + d.frames.length + ' 時点';
        const slider = $('replaySlider');
        slider.max = String(Math.max(0, d.frames.length - 1));
        const f = d.frames[Math.min(replay.frame, d.frames.length - 1)];
        if (!f) return;
        $('replayTime').textContent = 'ゲーム内 ' + (f.clock || f.second + '秒')
            + '（' + f.second + ' 秒経過）';
        $('replayBoards').innerHTML = f.boards.map(function (b) {
            const towers = Object.keys(b.towers).map(function (k) {
                return esc(k) + ' x' + b.towers[k];
            }).join(' / ') || 'なし';
            return '<div class="card"><div class="card-head">'
                + '<div class="card-title">' + esc(b.player) + '</div>'
                + '<div class="card-note">ライフ ' + b.lives + '/' + b.max_lives
                + ' ・ コイン ' + b.coins + ' ・ インカム ' + b.income
                + ' ・ ストック ' + b.stock + ' ・ 手札 ' + b.hand + '</div></div>'
                + '<pre class="log-view" style="line-height:1.15">'
                + esc(b.board.join('\n')) + '</pre>'
                + '<div class="card-note">経路長 ' + b.path_length
                + ' ・ 射程通過 ' + b.tower_passes
                + ' ・ 敵 ' + b.enemies + ' 体 ・ タワー ' + b.tower_count
                + '（' + towers + '）</div></div>';
        }).join('');
        $('sendBody').innerHTML = d.sends.length
            ? d.sends.slice().reverse().map(function (s) {
                return '<tr><td>' + esc(s.clock || (s.second + '秒')) + '</td><td>' + esc(s.player)
                    + '</td><td>' + esc(s.kind) + '</td><td class="num">'
                    + s.cost + '</td></tr>';
            }).join('')
            : '<tr><td colspan="4" class="empty">送りはまだありません</td></tr>';
    }

    async function pollReplay() {
        const st = await getJSON('/api/replay');
        if (st.running) {
            $('replayNote').textContent = '対局を生成中…';
            setTimeout(pollReplay, 1200);
            return;
        }
        if (st.error) { $('replayNote').textContent = 'エラー: ' + st.error.split('\n')[0]; return; }
        if (st.replay) { replay.data = st.replay; replay.frame = 0; renderReplay(); }
    }

    // ── バランス ──
    function renderBalance(report) {
        const icons = { error: '重大', warn: '注意', info: '良好' };
        $('findingList').innerHTML = report.findings.map(function (f, i) {
            let html = '<div class="log-line ' + (f.level === 'info' ? 'success' : f.level)
                + '"><b>[' + icons[f.level] + ']</b> (' + esc(f.area) + ') ' + esc(f.message);
            if (f.recommend) {
                const r = f.recommend;
                html += '<div style="margin-top:6px">推奨: <code>' + esc(r.path)
                    + '</code> ' + r.current + ' → <b>' + r.suggested + '</b> '
                    + '<button class="btn btn-ghost" data-apply="' + i + '">'
                    + 'balance.py に適用</button></div>';
            }
            return html + '</div>';
        }).join('') || '<div class="empty">指摘はありません</div>';

        document.querySelectorAll('#findingList [data-apply]').forEach(function (btn) {
            btn.addEventListener('click', async function () {
                const f = report.findings[parseInt(btn.dataset.apply, 10)];
                if (!confirm(f.recommend.path + ' を ' + f.recommend.current
                    + ' → ' + f.recommend.suggested + ' に変更します。よろしいですか？')) return;
                const res = await post('/api/balance/apply',
                    { path: f.recommend.path, value: f.recommend.suggested });
                alert(res.message);
                if (res.ok) runBalance(true);
            });
        });

        const titles = {
            tower_efficiency: 'タワーのコスト効率（弓塔 = 1.00）',
            attacker_value: '送りモンスターの価値',
            by_players: '人数別のバランス',
            symmetry: '守り vs 送り（実戦）',
            maze: '迷路', upgrade_wait: '強化 1 段の待ち時間',
            kill_recovery: '撃破報酬の回収率',
        };
        $('balanceTables').innerHTML = Object.keys(titles).filter(function (k) {
            return report.tables[k] && report.tables[k].length;
        }).map(function (k) {
            const rows = report.tables[k];
            const cols = Object.keys(rows[0]);
            return '<div class="card"><div class="card-head"><div class="card-title">'
                + esc(titles[k]) + '</div></div><div class="table-wrap"><div class="table-scroll">'
                + '<table><thead><tr>' + cols.map((c) => '<th>' + esc(c) + '</th>').join('')
                + '</tr></thead><tbody>' + rows.map(function (r) {
                    return '<tr>' + cols.map(function (c) {
                        const v = r[c];
                        return '<td class="num">' + (v === null || v === undefined ? '—' : esc(v)) + '</td>';
                    }).join('') + '</tr>';
                }).join('') + '</tbody></table></div></div></div>';
        }).join('');
    }

    async function pollBalance() {
        const st = await getJSON('/api/balance');
        if (st.running) {
            $('balanceNote').textContent = '診断を実行中…（実戦を回すので数十秒かかります）';
            setTimeout(pollBalance, 1500);
            return;
        }
        if (st.error) { $('balanceNote').textContent = 'エラー: ' + st.error.split('\n')[0]; return; }
        if (st.report) {
            $('balanceNote').textContent = '最終更新 ' + (st.updated || '');
            renderBalance(st.report);
        }
    }

    async function runBalance(simulate) {
        const res = await post('/api/balance/run', { simulate: !!simulate });
        if (!res.ok) { alert(res.message); return; }
        pollBalance();
    }

    // ── 通信 ──
    async function loadHistory() {
        const data = await getJSON('/api/history');
        state.history = data.records || [];
        renderProgress();
        renderTable();
        renderCharts();
        renderDictCharts();
    }

    function applyStatus(st) {
        state.status = st;
        renderProgress();
        // ログが変わったときだけ取り直す（毎回は取らない）
        if (st.log_version !== state.logVersion) {
            state.logVersion = st.log_version;
            loadHistory();
        }
    }

    function connect() {
        try {
            const es = new EventSource('/api/stream');
            es.onmessage = (ev) => applyStatus(JSON.parse(ev.data));
            es.onerror = () => { es.close(); setTimeout(connect, 3000); };
        } catch (e) {
            setInterval(async () => applyStatus(await getJSON('/api/status')), 2000);
        }
    }

    // ── 起動 ──
    /** サーバに繋がらないことを画面に出す（黙って無反応にしない） */
    function showOffline(err) {
        $('statusText').textContent = 'サーバに接続できません';
        $('statusDot').className = 'status-dot';
        $('progressNote').textContent =
            'サーバが起動していません（' + err + '）。dashboard/start.bat から起動してください。'
            + ' 5 秒ごとに再接続します。';
    }

    async function init() {
        // ── 通信より先に UI だけのハンドラを付ける ──
        // ここを await のあとに置くと、サーバが落ちているときに init() が
        // 例外で止まり、**設定ボタンもタブも一切効かない**状態になる。
        // 「押しても何も起きない」の原因になるので、順序は動かさないこと。
        const openPanel = (on) => {
            // app.css が定義しているのは .panel.active / .overlay.active
            $('settingsPanel').classList.toggle('active', on);
            $('settingsOverlay').classList.toggle('active', on);
        };
        $('settingsBtn').addEventListener('click', () => openPanel(true));
        $('settingsClose').addEventListener('click', () => openPanel(false));
        $('settingsOverlay').addEventListener('click', () => openPanel(false));

        // ── ここから先はサーバが要る。落ちていても UI は生かしたままにする ──
        try {
            state.config = await getJSON('/api/config');
        } catch (e) {
            showOffline(e.message || e);
            setTimeout(init, 5000);
            return;
        }
        document.title = state.config.title;
        $('appTitle').textContent = state.config.title;
        $('modeSelect').innerHTML = state.config.modes
            .map((m) => '<option value="' + esc(m) + '">' + esc(m) + '</option>').join('');

        document.querySelectorAll('#tabbar button').forEach(function (btn) {
            btn.addEventListener('click', function () {
                document.querySelectorAll('#tabbar button').forEach((b) => b.classList.remove('active'));
                document.querySelectorAll('.view').forEach((v) => v.classList.remove('active'));
                btn.classList.add('active');
                $('view-' + btn.dataset.tab).classList.add('active');
                if (btn.dataset.tab === 'charts') { renderCharts(); renderDictCharts(); }
                if (btn.dataset.tab === 'replay') pollReplay();
                if (btn.dataset.tab === 'balance') pollBalance();
            });
        });

        // ── 対局ビューア ──
        getJSON('/api/checkpoints').then(function (d) {
            $('replayCheckpoint').innerHTML =
                '<option value="">基準ボット同士</option>'
                + (d.checkpoints || []).map(function (c) {
                    return '<option value="' + esc(c) + '">' + esc(c) + '</option>';
                }).join('');
        });
        $('replayRun').addEventListener('click', async function () {
            const res = await post('/api/replay/run', {
                players: parseInt($('replayPlayers').value, 10),
                checkpoint: $('replayCheckpoint').value,
            });
            if (!res.ok) { alert(res.message); return; }
            pollReplay();
        });
        $('replaySlider').addEventListener('input', function () {
            replay.frame = parseInt(this.value, 10) || 0;
            renderReplay();
        });

        // ── バランス ──
        $('balanceRun').addEventListener('click', () => runBalance(true));
        $('balanceRunFast').addEventListener('click', () => runBalance(false));
        $('balanceSync').addEventListener('click', async function () {
            const d = await getJSON('/api/balance/sync');
            $('syncCard').style.display = '';
            $('syncNote').textContent = d.ok
                ? '一致 ' + d.matched + ' 項目'
                : '不一致 ' + (d.mismatch || []).length + ' 件 / 読めなかった '
                  + (d.unreadable || []).length + ' 件';
            const rows = (d.mismatch || []).map(function (m) {
                return '<div class="log-line error">' + esc(m.key)
                    + ' : Java=' + esc(m.java) + ' / Python=' + esc(m.python) + '</div>';
            }).concat((d.unreadable || []).map(function (k) {
                return '<div class="log-line warn">' + esc(k)
                    + ' : Java 側を読み取れませんでした</div>';
            }));
            $('syncBody').innerHTML = rows.length
                ? '<div class="log-view">' + rows.join('') + '</div>'
                : '<div class="empty">balance.py は Java の定義と完全に一致しています</div>';
        });
        document.querySelectorAll('#rangeButtons button').forEach(function (btn) {
            btn.addEventListener('click', function () {
                document.querySelectorAll('#rangeButtons button').forEach((b) => b.classList.remove('active'));
                btn.classList.add('active');
                state.range = parseInt(btn.dataset.range, 10);
                renderCharts();
            });
        });
        $('startBtn').addEventListener('click', async function () {
            const res = await post('/api/start', {
                mode: $('modeSelect').value,
                gens: parseInt($('gensInput').value, 10) || 20,
                num_envs: parseInt($('envsInput').value, 10) || 0,
                randomize: parseFloat($('randomizeInput').value) || 0,
                gen_early: parseFloat($('genEarlyInput').value) || 30,
                gen_max: parseFloat($('genMaxInput').value) || 60,
                finish_early: parseFloat($('finishEarlyInput').value) || 1.0,
                finish_late: parseFloat($('finishLateInput').value) || 0.9,
                match_max: parseFloat($('matchMaxInput').value) || 0,
            });
            if (!res.ok) alert(res.message);
            openPanel(false);
        });
        $('stopBtn').addEventListener('click', async function () {
            if (!confirm('学習を停止しますか？')) return;
            const res = await post('/api/stop');
            if (!res.ok) alert(res.message);
        });

        await loadHistory();
        connect();
    }

    init().catch((e) => alert('起動に失敗しました: ' + e));
})();
