/* ══════════════════════════════════════════════════
   学習ダッシュボード フロント（テンプレート）
   指標の定義は /api/config から受け取るので、
   ゲームごとにこのファイルを書き換える必要はない。
   ══════════════════════════════════════════════════ */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const state = { config: null, status: {}, history: [], range: 0, logVersion: null, startPending: false };

    // ── 表示ヘルパー ──
    const fmt = {
        rate: (v) => (v === null || v === undefined) ? '—' : (v * 100).toFixed(1) + '%',
        float: (v) => (v === null || v === undefined) ? '—' : Number(v).toFixed(4),
        int: (v) => (v === null || v === undefined) ? '—' : Math.round(v).toLocaleString('ja-JP'),
    };
    const show = (m, v) => (fmt[m.fmt] || fmt.float)(v);
    const num = (v, dec) => (v === null || v === undefined || !isFinite(v)) ? '—' : Number(v).toFixed(dec !== undefined ? dec : 2);
    const int = (v) => (v === null || v === undefined || !isFinite(v)) ? '—' : Math.round(v).toLocaleString('ja-JP');
    const pct = (v) => (v === null || v === undefined || !isFinite(v)) ? '—' : (v * 100).toFixed(1) + '%';
    const esc = (s) => String(s).replace(/[&<>"']/g,
        (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

    async function getJSON(url) {
        const res = await fetch(url);
        if (!res.ok) throw new Error(url + ' → ' + res.status);
        return res.json();
    }

    async function post(url, body) {
        // サーバが 500 を返すと本文は HTML なので res.json() が例外になる。
        // 呼び出し側は await post(...) を try で囲っていないため、
        // **押しても何も起きない**状態になっていた。必ず形の揃った
        // オブジェクトを返し、失敗を画面に出せるようにする。
        try {
            const res = await fetch(url, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body || {}),
            });
            const text = await res.text();
            try {
                return JSON.parse(text);
            } catch (e) {
                return { ok: false,
                         message: 'サーバの応答が JSON ではありません (HTTP '
                                  + res.status + '): ' + text.slice(0, 200) };
            }
        } catch (e) {
            return { ok: false, message: '通信に失敗しました: ' + (e.message || e) };
        }
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
        const runtime = (st.runtime === 'colab') ? 'Colab' : 'Local';
        const runtimeText = st.runtime_label || runtime;
        // 学習中は「いま回している世代」を出す。停止すると最後に完了した世代に戻る。
        const live = st.is_running ? st.live : null;
        const doneGen = latest ? latest.episode : 0;
        const showGen = (live && live.episode) ? live.episode : doneGen;

        // 「Colab に向けている」ことと「実際に繋がっている」ことは別物。
        // 分けて出さないと、有効にしただけで接続できたと誤解する
        const cb = st.colab || {};
        let badge = runtimeText;
        if (st.runtime === 'colab') badge += cb.ok ? '（接続OK）' : '（未接続）';
        $('runtimeState').textContent = badge;
        const badgeEl = $('runtimeBadge');
        if (badgeEl) {
            badgeEl.title = st.runtime === 'colab'
                ? (cb.url || '') + (cb.ok ? '' : ' / ' + (cb.last_error || '未接続'))
                : 'ローカルPCで学習します';
            badgeEl.style.color = st.runtime !== 'colab' ? ''
                : (cb.ok ? '#4caf50' : '#f44336');
        }

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

        const noteBase = st.is_running ? (st.mode || '学習中')
            : (recs.length ? '待機中（記録済み ' + recs.length + ' 世代）' : '待機中');
        $('progressNote').textContent = '実行先: ' + runtimeText + ' / ' + noteBase;
        $('statusText').textContent = st.is_running ? '学習中' : '待機中';
        $('statusDot').className = 'status-dot' + (st.is_running ? ' running' : '');
        $('statusGen').textContent = showGen ? '第 ' + showGen + ' 世代' : '';
        $('startBtn').disabled = !!st.is_running || state.startPending;
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

    const PALETTE = [
        '#2196f3', '#f44336', '#4caf50', '#ff9800', '#9c27b0', 
        '#00bcd4', '#e91e63', '#8bc34a', '#ffc107', '#3f51b5',
        '#009688', '#ff5722'
    ];

    function renderTable() {
        const body = $('genTableBody');
        const rows = state.history.slice().reverse().slice(0, 500);
        $('genCountNote').textContent = state.history.length ? state.history.length + ' 世代を記録中（行をクリックすると下に詳細が表示されます）' : 'まだ世代的の記録がありません';

        if (!rows.length) {
            body.innerHTML = '<tr><td colspan="5" class="empty">まだ世代の記録がありません</td></tr>';
            $('genDetailCard').style.display = 'none';
            return;
        }

        body.innerHTML = rows.map(function (r) {
            const isSel = state.selectedGen === r.episode;
            const selClass = isSel ? ' class="selected" style="background:rgba(33,150,243,0.15); cursor:pointer;"' : ' style="cursor:pointer;"';
            const gm = r.game_minutes !== undefined ? num(r.game_minutes, 1) + ' 分' : '—';
            const sec = r.seconds_per_gen !== undefined ? num(r.seconds_per_gen, 1) + ' 秒' : '—';
            const games = r.games_finished !== undefined ? int(r.games_finished) : '—';
            const fin = r.finish_rate !== undefined ? pct(r.finish_rate) : '—';

            return '<tr data-ep="' + r.episode + '"' + selClass + '>'
                + '<td><b>第 ' + int(r.episode) + ' 世代</b></td>'
                + '<td class="num">' + gm + '</td>'
                + '<td class="num">' + sec + '</td>'
                + '<td class="num">' + games + '</td>'
                + '<td class="num"><span class="badge badge-rate">' + fin + '</span></td>'
                + '</tr>';
        }).join('');

        body.querySelectorAll('tr[data-ep]').forEach(function (tr) {
            tr.addEventListener('click', function () {
                state.selectedGen = Number(tr.dataset.ep);
                renderTable();
            });
        });

        renderGenDetail();
    }

    function renderGenDetail() {
        const detailCard = $('genDetailCard');
        if (!state.selectedGen) {
            if (state.history.length) {
                state.selectedGen = state.history[state.history.length - 1].episode;
            } else {
                detailCard.style.display = 'none';
                return;
            }
        }
        const rec = state.history.find(r => r.episode === state.selectedGen);
        if (!rec) {
            detailCard.style.display = 'none';
            return;
        }

        detailCard.style.display = '';
        $('genDetailTitle').textContent = '第 ' + rec.episode + ' 世代の詳細指標';

        const fmtPctDict = function (d) {
            if (!d || !Object.keys(d).length) return '<div class="empty">なし</div>';
            return Object.keys(d).map(function (k, i) {
                const color = PALETTE[i % PALETTE.length];
                const val = (d[k] * 100).toFixed(1) + '%';
                return '<div style="display:inline-block; margin-right:12px; margin-bottom:4px;">'
                    + '<span style="display:inline-block; width:10px; height:10px; background:' + color + '; border-radius:2px; margin-right:4px;"></span>'
                    + esc(k) + ': <b>' + val + '</b></div>';
            }).join('');
        };

        const tiles = [
            tile('勝率 vs ランダム', rec.win_vs_random !== undefined ? pct(rec.win_vs_random) : '—', '', '評価マッチ結果'),
            tile('勝率 vs 過去最強', rec.win_vs_best !== undefined ? pct(rec.win_vs_best) : '—', '', 'Elo: ' + (rec.elo || '1000')),
            tile('Loss / KL', (rec.loss !== undefined ? num(rec.loss, 4) : '—') + ' / ' + (rec.kl !== undefined ? num(rec.kl, 4) : '—'), '', 'PPO更新結果'),
            tile('タワー平均レベル', rec.tower_avg_level !== undefined ? num(rec.tower_avg_level, 2) : '—', 'Lv', '最終配置の平均レベル'),
            tile('最終タワー数', rec.tower_count_final !== undefined ? num(rec.tower_count_final, 1) : '—', '基', '全島平均'),
            tile('平均経路長 / 射程通過', (rec.avg_path_length ? num(rec.avg_path_length, 1) : '—') + ' / ' + (rec.avg_tower_passes ? num(rec.avg_tower_passes, 1) : '—'), '', '迷路性能'),
        ];

        let html = '<div class="tiles" style="grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap:12px;">' + tiles.join('') + '</div>';

        html += '<div style="margin-top:16px; display:flex; flex-direction:column; gap:12px;">';
        html += '<div class="card" style="background:rgba(0,0,0,0.2); padding:12px; border-radius:8px;"><div style="font-weight:bold; margin-bottom:6px; color:#4caf50;">🏰 タワーの使用割合 (%)</div>' + fmtPctDict(rec.tower_type_rates) + '</div>';
        html += '<div class="card" style="background:rgba(0,0,0,0.2); padding:12px; border-radius:8px;"><div style="font-weight:bold; margin-bottom:6px; color:#ff9800;">🚀 モンスターの送り割合 (%)</div>' + fmtPctDict(rec.send_type_rates) + '</div>';
        html += '<div class="card" style="background:rgba(0,0,0,0.2); padding:12px; border-radius:8px;"><div style="font-weight:bold; margin-bottom:6px; color:#f44336;">💀 モンスターの漏れ割合 (%)</div>' + fmtPctDict(rec.leak_type_rates) + '</div>';
        html += '</div>';
        html += allMetricsHtml(rec);

        $('genDetailTiles').innerHTML = html;
    }

    // ── 全指標。**ログに入っている値をすべて出す。** ────────────────
    // 個別に並べたタイルは「まず見る 6 つ」で、それ以外は学習が進むにつれて
    // 増える（勝者/敗者の内訳・分位・種類別ダメージ・定点観測など）。
    // ここはキーを決め打ちせずに rec を走査するので、train.py 側で指標を
    // 足しただけでダッシュボードにも自動で出る
    const SKIP_KEYS = { episode: 1, timestamp: 1, live: 1 };

    function looksLikeRate(key) {
        return /_(rate|rates|mix|share|share_by_kind)$/.test(key)
            || /^(win_vs_|match_completion|card_usage_rate|finish_rate)/.test(key);
    }

    function fmtDict(key, d) {
        const asPct = looksLikeRate(key);
        return Object.keys(d).sort().map(function (k, i) {
            const color = PALETTE[i % PALETTE.length];
            const v = d[k];
            const val = asPct ? (v * 100).toFixed(1) + '%' : num(v, 3);
            return '<div style="display:inline-block; margin-right:12px; margin-bottom:4px;">'
                + '<span style="display:inline-block; width:10px; height:10px; background:'
                + color + '; border-radius:2px; margin-right:4px;"></span>'
                + esc(k) + ': <b>' + val + '</b></div>';
        }).join('');
    }

    function allMetricsHtml(rec) {
        const nums = [], dicts = [], strs = [];
        Object.keys(rec).sort().forEach(function (k) {
            if (SKIP_KEYS[k]) return;
            const v = rec[k];
            if (v === null || v === undefined) return;
            if (typeof v === 'number') nums.push([k, v]);
            else if (typeof v === 'string') strs.push([k, v]);
            else if (typeof v === 'object') dicts.push([k, v]);
        });
        if (!nums.length && !dicts.length && !strs.length) return '';

        let h = '<details open style="margin-top:16px;">'
            + '<summary style="cursor:pointer; font-weight:bold; padding:6px 0;">'
            + '📋 全指標（' + (nums.length + dicts.length + strs.length)
            + ' 項目 / ログにある値をすべて表示）</summary>';

        if (strs.length) {
            h += '<div style="margin:8px 0; display:flex; flex-wrap:wrap; gap:8px;">'
                + strs.map(function (kv) {
                    return '<div style="background:rgba(0,0,0,0.2); padding:4px 10px;'
                        + ' border-radius:6px;"><span style="opacity:.7;">' + esc(kv[0])
                        + '</span> <b>' + esc(kv[1]) + '</b></div>';
                }).join('') + '</div>';
        }
        if (nums.length) {
            h += '<div style="display:grid; grid-template-columns:'
                + ' repeat(auto-fit, minmax(240px, 1fr)); gap:2px 16px; margin:8px 0;">'
                + nums.map(function (kv) {
                    const asPct = looksLikeRate(kv[0]);
                    const val = asPct ? pct(kv[1]) : num(kv[1], 4);
                    return '<div style="display:flex; justify-content:space-between;'
                        + ' padding:3px 8px; border-bottom:1px solid rgba(255,255,255,0.06);">'
                        + '<span style="opacity:.75;">' + esc(kv[0]) + '</span>'
                        + '<b>' + val + '</b></div>';
                }).join('') + '</div>';
        }
        dicts.forEach(function (kv) {
            h += '<div class="card" style="background:rgba(0,0,0,0.2); padding:12px;'
                + ' border-radius:8px; margin-top:8px;">'
                + '<div style="font-weight:bold; margin-bottom:6px; opacity:.85;">'
                + esc(kv[0]) + '</div>' + fmtDict(kv[0], kv[1]) + '</div>';
        });
        return h + '</details>';
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

    // ── マルチカラー辞書指標グラフ（タワー割合・送り割合・漏れ割合・人数別勝率） ──
    const dictTitles = {
        win_rate_by_players: '人数別の勝率',
        tower_type_rates: 'タワーの使用割合 (%)',
        send_type_rates: 'モンスターの送り割合 (%)',
        leak_type_rates: 'モンスターの漏れ割合 (%)',
    };

    function renderDictCharts() {
        const box = $('playerCharts');
        if (!box) return;
        const recs = state.range > 0 ? state.history.slice(-state.range) : state.history;
        const xMin = recs.length ? recs[0].episode : undefined;
        const xMax = recs.length ? recs[recs.length - 1].episode : undefined;
        // 同じ軸を渡して、人数どうしの高さを直接比べられるようにする
        const axis = { label: '%', min: 0, max: 100, format: (v) => v.toFixed(0) + '%' };

        // 「名前 → 数値」の入れ子辞書指標（人数別勝率・使用割合など）は、
        // 中の名前（2p / 4p / 8p・タワー名・モンスター名）ごとに小グラフを分けて並べる。
        // 世代が進むと名前が増えることがあるので、記録全体から名前を集める。
        const keys = ((state.config && state.config.dict_metrics) || Object.keys(dictTitles))
            .filter(function (k) { return dictTitles[k] !== undefined; });
        const namesByKey = keys.map(function (key) {
            const names = [];
            recs.forEach(function (r) {
                const d = r[key];
                if (!d) return;
                Object.keys(d).forEach(function (n) {
                    if (names.indexOf(n) === -1) names.push(n);
                });
            });
            return names;
        });

        let nextId = 0;
        box.innerHTML = keys.map(function (key, mi) {
            const names = namesByKey[mi];
            if (!names.length) return '';
            return '<div class="card"><div class="card-head"><div class="card-title">'
                + esc(dictTitles[key]) + '</div></div><div class="small-multiples">'
                + names.map(function () {
                    return '<div class="chart-box" id="pc' + (nextId++) + '"></div>';
                }).join('') + '</div></div>';
        }).join('') || '<div class="empty">まだ記録がありません。</div>';

        let id = 0;
        keys.forEach(function (key, mi) {
            namesByKey[mi].forEach(function (n) {
                const points = recs.map(function (r) {
                    const d = r[key];
                    const v = d ? d[n] : null;
                    return { x: r.episode, y: (v === null || v === undefined) ? null : v * 100 };
                });
                MDDChart.render($('pc' + (id++)), {
                    height: 200, xLabel: '世代', xMin: xMin, xMax: xMax,
                    series: [{ key: n, label: n, points: points,
                               tipFormat: (v) => v.toFixed(1) + '%' }],
                    axes: { left: axis },
                    emptyText: 'まだ記録がありません。',
                });
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

    // ══════════════════════════════════════════════════
    // 数値編集（balance.py と Java enum の両方へ書き戻す）
    // ══════════════════════════════════════════════════
    const balEdit = { data: null, group: 'towers', dirty: {}, q: '', loading: false };

    function balEditCount() { return Object.keys(balEdit.dirty).length; }

    function balEditSyncButtons() {
        const n = balEditCount();
        const locked = !!(balEdit.data && balEdit.data.locked);
        $('balEditSave').disabled = locked || n === 0;
        $('balEditReset').disabled = n === 0;
        $('balEditSave').textContent = n ? '保存（' + n + ' 項目）' : '保存';
    }

    /** 数値欄 1 つ。**元の値を data-orig に持たせて、変更の有無を毎回そこから判定する。** */
    function balEditField(f) {
        const dirty = Object.prototype.hasOwnProperty.call(balEdit.dirty, f.path);
        const value = dirty ? balEdit.dirty[f.path] : f.value;
        const locked = !!(balEdit.data && balEdit.data.locked);
        const cls = 'bal-f' + (dirty ? ' dirty' : '') + (f.same === false ? ' diff' : '');
        const title = f.same === false
            ? 'balance.py=' + f.value + ' / Java=' + f.java + '（保存すると両方この値になります）'
            : f.path;
        return '<label class="' + cls + '" title="' + esc(title) + '">'
            + '<span>' + esc(f.label) + (f.unit ? ' <em>' + esc(f.unit) + '</em>' : '') + '</span>'
            + '<input type="number" data-path="' + esc(f.path) + '"'
            + ' data-orig="' + esc(String(f.value)) + '"'
            + ' step="' + (f.kind === 'int' ? '1' : '0.01') + '"'
            + (locked || !f.editable ? ' disabled' : '')
            + ' value="' + esc(String(value)) + '"></label>';
    }

    function renderBalEditor() {
        const d = balEdit.data;
        if (!d) return;
        $('balEditGroups').innerHTML = d.groups.map(function (g) {
            return '<button data-group="' + esc(g.key) + '"'
                + (g.key === balEdit.group ? ' class="active"' : '') + '>'
                + esc(g.label) + '</button>';
        }).join('');
        $('balEditGroups').querySelectorAll('button').forEach(function (b) {
            b.addEventListener('click', function () {
                balEdit.group = b.dataset.group;
                renderBalEditor();
            });
        });

        const group = d.groups.filter((g) => g.key === balEdit.group)[0];
        const q = balEdit.q.trim().toLowerCase();
        const rows = (group ? group.rows : []).filter(function (r) {
            return !q || (r.id + ' ' + r.label).toLowerCase().indexOf(q) >= 0;
        });
        if (!rows.length) {
            $('balEditBody').innerHTML = '<div class="card"><div class="empty">'
                + '該当する項目がありません</div></div>';
            balEditSyncButtons();
            return;
        }
        $('balEditBody').innerHTML = rows.map(function (r) {
            let html = '<div class="card bal-row"><div class="card-head">'
                + '<div class="card-title">' + esc(r.label)
                + ' <span class="bal-id">' + esc(r.id) + '</span></div>'
                + '<div class="card-note">' + esc(r.note || '') + '</div></div>'
                + '<div class="bal-grid">' + r.fields.map(balEditField).join('') + '</div>';
            (r.subs || []).forEach(function (sub) {
                html += '<div class="bal-sub"><div class="bal-sub-title">'
                    + esc(sub.label) + '</div><div class="bal-grid">'
                    + sub.fields.map(balEditField).join('') + '</div></div>';
            });
            return html + '</div>';
        }).join('');

        $('balEditBody').querySelectorAll('input[data-path]').forEach(function (input) {
            input.addEventListener('input', function () {
                const path = input.dataset.path;
                const orig = parseFloat(input.dataset.orig);
                const now = parseFloat(input.value);
                // 空欄や数字でない入力は「まだ書きかけ」。消すのではなく無視する
                if (!Number.isFinite(now)) { delete balEdit.dirty[path]; }
                else if (Math.abs(now - orig) < 1e-9) { delete balEdit.dirty[path]; }
                else { balEdit.dirty[path] = now; }
                input.parentElement.classList.toggle(
                    'dirty', Object.prototype.hasOwnProperty.call(balEdit.dirty, path));
                balEditSyncButtons();
            });
        });
        balEditSyncButtons();
    }

    async function loadBalEditor(force) {
        if (balEdit.loading) return;
        if (balEdit.data && !force) { renderBalEditor(); return; }
        balEdit.loading = true;
        $('balEditNote').textContent = '読み込み中…';
        try {
            const d = await getJSON('/api/balance/editor');
            if (!d.ok) {
                $('balEditNote').textContent = 'エラー: ' + String(d.error || '').split('\n')[0];
                return;
            }
            balEdit.data = d;
            let n = 0;
            d.groups.forEach((g) => g.rows.forEach(function (r) {
                n += r.fields.length;
                (r.subs || []).forEach((s) => { n += s.fields.length; });
            }));
            $('balEditNote').textContent = n + ' 項目';
            const banner = [];
            if (d.locked) {
                banner.push('<b>学習中なので保存できません。</b>学習を停止してから変更してください'
                    + '（走っている学習は起動時の値で動いているため、途中で書き換えても効きません）');
            }
            if (d.unwritable_total) {
                banner.push('Java 側の書き込み先が見つからない項目が '
                    + d.unwritable_total + ' 件あります: ' + esc(d.unwritable.join(', ')));
            }
            $('balEditBanner').style.display = banner.length ? '' : 'none';
            $('balEditBannerText').innerHTML = banner.join('<br>');
            renderBalEditor();
        } finally {
            balEdit.loading = false;
        }
    }

    async function saveBalEditor() {
        const changes = Object.keys(balEdit.dirty)
            .map((path) => ({ path: path, value: balEdit.dirty[path] }));
        if (!changes.length) return;
        if (!confirm(changes.length + ' 項目を balance.py と Java の両方に書き込みます。\n'
            + '実ゲームに反映するにはビルドとサーバー再起動が必要です。\nよろしいですか？')) return;
        $('balEditSave').disabled = true;
        $('balEditNote').textContent = '保存中…';
        const res = await post('/api/balance/editor/apply', { changes: changes });
        if (!res.ok) {
            alert(res.message || '保存に失敗しました');
            $('balEditNote').textContent = '保存に失敗しました';
            balEditSyncButtons();
            return;
        }
        balEdit.dirty = {};
        await loadBalEditor(true);
        $('balEditNote').textContent = res.message;
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
            if (btn.dataset.tab === 'editor') loadBalEditor(false);
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
        $('balEditSave').addEventListener('click', saveBalEditor);
        $('balEditReset').addEventListener('click', function () {
            balEdit.dirty = {};
            renderBalEditor();
        });
        $('balEditSearch').addEventListener('input', function () {
            balEdit.q = this.value;
            renderBalEditor();
        });
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
            if (state.startPending) return;
            state.startPending = true;
            $('startBtn').disabled = true;
            try {
                // 0 は「無限」なので || で 20 に落としてはいけない
                const gens = parseInt($('gensInput').value, 10);
                const res = await post('/api/start', {
                    mode: $('modeSelect').value,
                    gens: (Number.isFinite(gens) && gens >= 0) ? gens : 20,
                    num_envs: parseInt($('envsInput').value, 10) || 0,
                    randomize: parseFloat($('randomizeInput').value) || 0,
                    gen_early: parseFloat($('genEarlyInput').value) || 20,
                    gen_max: parseFloat($('genMaxInput').value) || 30,
                    finish_early: parseFloat($('finishEarlyInput').value) || 1.0,
                    finish_late: parseFloat($('finishLateInput').value) || 0.9,
                    match_max: parseFloat($('matchMaxInput').value) || 0,
                });
                if (!res.ok) {
                    alert(res.message || '学習を開始できませんでした');
                    setTimeout(() => {
                        state.startPending = false;
                        $('startBtn').disabled = !!state.status.is_running;
                    }, 1200);
                    return;
                }
                openPanel(false);
            } finally {
                if (state.status && state.status.is_running) {
                    state.startPending = false;
                }
            }
        });
        $('stopBtn').addEventListener('click', async function () {
            if (!confirm('学習を停止しますか？')) return;
            const res = await post('/api/stop');
            if (!res.ok) alert(res.message);
        });

        // ── Colab / Drive 連携 ──
        async function updateDriveUI() {
            const st = await getJSON('/api/drive/state');
            if (!st) return;
            const notice = $('driveNotice');
            const connBtn = $('driveConnectBtn');
            const disconnBtn = $('driveDisconnectBtn');
            const syncBtn = $('driveSyncBtn');
            const uploadCodeBtn = $('driveUploadCodeBtn');
            const statusMsg = $('driveStatusMsg');
            const errorMsg = $('driveErrorMsg');

            let noticeHtml = '';
            if (!st.libs_available) {
                noticeHtml = '⚠️ `google-api-python-client` などのライブラリがありません。<br><code>pip install google-api-python-client google-auth-httplib2 google-auth-oauthlib</code> を実行してください。';
                connBtn.disabled = true;
            } else if (!st.has_credentials) {
                noticeHtml = '⚠️ `credentials.json` が見つかりません。<br>Google Cloud Console で OAuth クライアント ID (デスクトップアプリ) を作成し、<code>' + esc(st.credentials_path) + '</code> に配置してください。';
                connBtn.disabled = true;
            } else if (st.connected) {
                noticeHtml = '✅ Google Drive に接続済みです (フォルダ: <b>' + esc(st.folder) + '</b>)';
                connBtn.style.display = 'none';
                disconnBtn.style.display = '';
                syncBtn.disabled = st.busy;
                uploadCodeBtn.disabled = st.busy;
            } else {
                noticeHtml = '💡 Google Drive 未接続。「Drive に接続」ボタンで認証を行ってください。';
                connBtn.style.display = '';
                connBtn.disabled = st.connecting;
                disconnBtn.style.display = 'none';
                syncBtn.disabled = true;
                uploadCodeBtn.disabled = true;
            }
            notice.innerHTML = noticeHtml;

            if (st.message) {
                statusMsg.style.display = '';
                statusMsg.textContent = st.message;
            } else {
                statusMsg.style.display = 'none';
            }

            if (st.error) {
                errorMsg.style.display = '';
                errorMsg.textContent = st.error;
            } else {
                errorMsg.style.display = 'none';
            }

            const list = $('driveFileList');
            if (st.local_files && st.local_files.length) {
                list.innerHTML = st.local_files.map(f => `<div>📄 <b>${esc(f.name)}</b> (${(f.size/1024).toFixed(1)} KB) - ${f.modified}</div>`).join('');
            } else {
                list.innerHTML = 'ファイルはありません';
            }
        }

        $('driveConnectBtn').addEventListener('click', async function() {
            const res = await post('/api/drive/connect');
            if (!res.ok) alert(res.message);
            updateDriveUI();
        });

        $('driveDisconnectBtn').addEventListener('click', async function() {
            if (!confirm('Drive 接続を解除しますか？')) return;
            const res = await post('/api/drive/disconnect');
            if (!res.ok) alert(res.message);
            updateDriveUI();
        });

        $('driveSyncBtn').addEventListener('click', async function() {
            const res = await post('/api/drive/sync', { action: 'download', include_checkpoints: false });
            if (!res.ok) alert(res.message);
            updateDriveUI();
        });

        $('driveUploadCodeBtn').addEventListener('click', async function() {
            const res = await post('/api/drive/upload_code');
            if (!res.ok) alert(res.message);
            updateDriveUI();
        });

        setInterval(updateDriveUI, 3000);
        updateDriveUI();

        // ── 学習の受け渡し ──
        async function loadHandoff() {
            const st = await getJSON('/api/handoff/state');
            const set = (id, v) => { const el = $(id); if (el) el.textContent = v; };
            const L = st.local || {}, D = st.drive || {};
            set('hoLocalGen', (L.generation || 0) + ' 世代');
            set('hoLocalSub', (L.records || 0) + ' 件の記録 / '
                + (L.checkpoint ? 'モデルあり' : 'モデルなし')
                + (L.updated ? ' / ' + L.updated : ''));
            set('hoDriveGen', D.available ? (D.generation || 0) + ' 世代' : '—');
            set('hoDriveSub', D.available
                ? (D.records || 0) + ' 件の記録 / '
                  + (D.checkpoint ? 'モデルあり' : 'モデルなし')
                  + (D.updated ? ' / ' + D.updated : '')
                : (st.error || 'Drive に未接続'));
            const adv = $('hoAdvice');
            if (adv) adv.textContent = st.advice || '';
            const msg = $('hoMsg');
            if (msg) {
                msg.textContent = st.busy ? '受け渡し中…' : (st.error || st.message || '');
                msg.style.color = st.error ? '#f44336' : '#4caf50';
            }
            ['hoPull', 'hoPush'].forEach((id) => { if ($(id)) $(id).disabled = !!st.busy; });
            if (st.busy) setTimeout(loadHandoff, 1500);
        }
        async function runHandoff(action, label) {
            let res = await post('/api/handoff/run', { action: action });
            if (res.ok === false) { alert(res.message); return; }
            // 実行結果は state から拾う（相手のほうが進んでいると確認を求められる）
            setTimeout(async function check() {
                const st = await getJSON('/api/handoff/state');
                if (st.busy) { setTimeout(check, 1000); return; }
                if (st.error && st.error.indexOf('進んでいます') >= 0) {
                    if (confirm(st.error + ' 本当に上書きしますか？')) {

                        await post('/api/handoff/run', { action: action, confirm: true });
                    }
                }
                await loadHandoff();
                await loadHistory();
            }, 800);
        }
        if ($('hoPull')) {
            $('hoPull').addEventListener('click', () => runHandoff('pull'));
            $('hoPush').addEventListener('click', () => runHandoff('push'));
            $('hoRefresh').addEventListener('click', loadHandoff);
        }

        // ── Colab リモート制御（ngrok） ──
        async function loadColabState() {
            const st = await getJSON('/api/colab/state');
            $('colabUrl').value = st.url || '';
            $('colabToken').value = st.token || '';
            $('colabEnabled').checked = !!st.enabled;
            // コピー用の表示。毎回 Colab の出力を探さずに済むようにする
            const show = (id, v) => { const el = $(id); if (el) el.textContent = v || '—'; };
            show('colabUrlShow', st.url);
            show('colabTokenShow', st.token);
            renderColabStatus(st);
        }
        function renderColabStatus(st) {
            const el = $('colabStatus');
            if (!el) return;
            let html = st.ok
                ? '✅ 接続OK（最終確認 ' + esc(st.last_check || '') + '）'
                : (st.last_check ? '❌ 接続NG（' + esc(st.last_error || '') + '）' : 'まだ接続テストしていません');
            if (st.enabled && !st.ok) html += '<div style="color:#ff9800;">⚠️ 有効なのに接続テスト未成功です。URL・トークン・ngrok を確認してください</div>';
            el.innerHTML = html;
        }
        $('colabSave').addEventListener('click', async function () {
            const res = await post('/api/colab/config', {
                url: $('colabUrl').value.trim(),
                token: $('colabToken').value.trim(),
                enabled: $('colabEnabled').checked,
            });
            if (!res.ok) alert(res.message || '保存に失敗しました');
            await loadColabState();
        loadHandoff().catch(() => {});
        });
        // 切替はチェックボックスを触った瞬間に反映する。
        // 以前は「接続設定を保存」を押すまで反映されず、
        // **ローカルと Colab を切り替えられない**ように見えていた
        $('colabEnabled').addEventListener('change', async function () {
            const on = $('colabEnabled').checked;
            const res = await post('/api/colab/config', { enabled: on });
            if (res.ok === false) {
                alert(res.message || '切り替えに失敗しました');
                $('colabEnabled').checked = !on;
                return;
            }
            await loadColabState();
            applyStatus(await getJSON('/api/status'));
            await loadHistory();
        });
        $('colabTest').addEventListener('click', async function () {
            $('colabStatus').textContent = '接続テスト中…';
            const res = await post('/api/colab/test', {});
            renderColabStatus(Object.assign({}, res, {
                ok: !!res.ok, enabled: $('colabEnabled').checked,
                last_check: new Date().toLocaleTimeString('ja-JP'),
                last_error: res.error || res.message || '',
            }));
            applyStatus(await getJSON('/api/status'));
            alert(res.ok ? 'Colab に接続できました。'
                         : '接続できませんでした: ' + (res.error || res.message || ''));
        });
        // コピーボタン（クリップボードが使えない環境では選択状態にする）
        document.querySelectorAll('[data-copy]').forEach(function (btn) {
            btn.addEventListener('click', async function () {
                const el = $(btn.dataset.copy);
                if (!el) return;
                const text = el.textContent.trim();
                if (!text || text === '—') return;
                try {
                    await navigator.clipboard.writeText(text);
                    const old = btn.textContent;
                    btn.textContent = 'コピーしました';
                    setTimeout(() => { btn.textContent = old; }, 1200);
                } catch (e) {
                    const r = document.createRange();
                    r.selectNodeContents(el);
                    const sel = window.getSelection();
                    sel.removeAllRanges(); sel.addRange(r);
                }
            });
        });
        await loadColabState();

        await loadHistory();
        connect();
    }

    init().catch((e) => alert('起動に失敗しました: ' + e));
})();
