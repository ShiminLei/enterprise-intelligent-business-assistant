/* 项目管理智能助手：对话页面。事件流契约见 specs/001-project-management-assistant/contracts/chat-stream-events.md */
(function () {
    'use strict';

    const state = {
        me: null,
        conversations: [],
        currentId: null,
        busy: false
    };

    const $ = (id) => document.getElementById(id);
    const el = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    };

    // ---------- 请求 ----------

    function csrfToken() {
        const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]+)/);
        return m ? decodeURIComponent(m[1]) : '';
    }

    function toLogin() {
        sessionStorage.setItem('returnTo', location.hash || '');
        location.href = '/login.html';
    }

    async function api(path, options = {}) {
        const opts = {credentials: 'same-origin', ...options, headers: {...(options.headers || {})}};
        if (opts.method && opts.method !== 'GET') {
            opts.headers['X-XSRF-TOKEN'] = csrfToken();
        }
        const res = await fetch(path, opts);
        if (res.status === 401) {
            toLogin();
            throw new Error('unauthorized');
        }
        return res;
    }

    async function errorMessage(res, fallback) {
        try {
            const body = await res.json();
            return body.message || fallback;
        } catch (e) {
            return fallback;
        }
    }

    // ---------- Markdown ----------

    function renderMarkdown(text) {
        const html = DOMPurify.sanitize(marked.parse(text || ''));
        const wrapper = el('div', 'content');
        wrapper.innerHTML = html;
        wrapper.querySelectorAll('table').forEach((table) => {
            const wrap = el('div', 'table-wrap');
            table.replaceWith(wrap);
            wrap.appendChild(table);
        });
        return wrapper;
    }

    // ---------- 顶栏与会话列表 ----------

    async function loadMe() {
        const res = await api('/api/me');
        state.me = await res.json();
        $('me').textContent = `${state.me.name}（${state.me.roleLabel}）`;
    }

    async function loadConversations() {
        const res = await api('/api/conversations');
        state.conversations = await res.json();
        renderConversationList();
    }

    function renderConversationList() {
        const list = $('conversation-list');
        list.replaceChildren();
        if (state.conversations.length === 0) {
            list.appendChild(el('div', 'muted conv-item', '暂无会话'));
            return;
        }
        state.conversations.forEach((c) => {
            const item = el('button', 'conv-item' + (c.id === state.currentId ? ' active' : ''), c.title || '新会话');
            item.type = 'button';
            item.title = c.title || '新会话';
            item.addEventListener('click', () => openConversation(c.id));
            list.appendChild(item);
        });
    }

    async function createConversation() {
        const res = await api('/api/conversations', {method: 'POST'});
        const c = await res.json();
        state.conversations.unshift(c);
        return c.id;
    }

    async function openConversation(id) {
        showView('chat');
        state.currentId = id;
        location.hash = id ? `c=${id}` : '';
        renderConversationList();
        const box = $('messages');
        box.replaceChildren();
        if (!id) {
            showEmptyHint();
            return;
        }
        const res = await api(`/api/conversations/${id}/messages`);
        if (!res.ok) {
            state.currentId = null;
            location.hash = '';
            showEmptyHint();
            return;
        }
        const messages = await res.json();
        if (messages.length === 0) {
            showEmptyHint();
        }
        messages.forEach((m) => {
            if (m.role === 'USER') {
                appendUserMessage(m.content);
            } else {
                const view = createAssistantMessage();
                (m.steps || []).forEach((s) => view.stepFinished({...s, finishedOnLoad: true}));
                view.answer(m);
                (m.pendingActions || []).forEach((a) => view.addPendingAction(a));
                view.collapseSteps();
            }
        });
        scrollToBottom();
    }

    function showEmptyHint() {
        const hint = $('empty-hint').content.cloneNode(true);
        hint.querySelectorAll('.examples button').forEach((b) =>
            b.addEventListener('click', () => {
                $('input').value = b.textContent;
                $('input').focus();
            }));
        $('messages').appendChild(hint);
    }

    // ---------- 消息渲染 ----------

    function scrollToBottom() {
        const box = $('messages');
        box.scrollTop = box.scrollHeight;
    }

    function appendUserMessage(text) {
        $('messages').querySelector('.empty-hint')?.remove();
        $('messages').appendChild(el('div', 'msg-user', text));
        scrollToBottom();
    }

    /** 创建一条助手消息，返回用于逐步更新它的方法。 */
    function createAssistantMessage() {
        const root = el('div', 'msg-assistant');
        const toggle = el('button', 'steps-toggle');
        toggle.type = 'button';
        toggle.hidden = true;
        const steps = el('ul', 'steps');
        const thinking = el('div', 'thinking', '正在思考…');
        const body = el('div');
        const actions = el('div', 'pending-actions');
        root.append(toggle, steps, thinking, body, actions);
        $('messages').appendChild(root);
        const stepItems = new Map();

        toggle.addEventListener('click', () => {
            steps.hidden = !steps.hidden;
            toggle.textContent = (steps.hidden ? '▸ ' : '▾ ') + `共 ${stepItems.size} 步`;
        });

        function stepItem(seq) {
            if (!stepItems.has(seq)) {
                const li = el('li');
                li.append(el('span', 'icon'), el('span', 'text'));
                steps.appendChild(li);
                stepItems.set(seq, li);
            }
            return stepItems.get(seq);
        }

        return {
            root,
            stepStarted(e) {
                const li = stepItem(e.seq);
                li.className = 'running';
                li.dataset.label = e.label;
                li.querySelector('.text').textContent = `正在${e.label}…`;
                thinking.hidden = true;
                scrollToBottom();
            },
            stepFinished(e) {
                const li = stepItem(e.seq);
                const label = e.label || li.dataset.label || e.tool;
                li.className = e.success ? 'ok' : 'fail';
                li.querySelector('.icon').textContent = e.success ? '✓' : '✗';
                li.querySelector('.text').textContent = `${label}：${e.resultSummary}`;
                // 历史消息直接显示结果；实时处理中则提示模型正在整理
                thinking.hidden = Boolean(e.finishedOnLoad);
                thinking.textContent = '正在整理结果…';
                scrollToBottom();
            },
            answer(a) {
                thinking.hidden = true;
                root.classList.toggle('failed', a.status === 'FAILED');
                const content = renderMarkdown(a.content);
                body.replaceChildren(content);
                if (a.status === 'STEP_LIMIT_REACHED') {
                    body.appendChild(el('div', 'notice', '已达到单次请求的工具调用上限'));
                }
                const citationBox = renderCitations(body, a.citations || []);
                linkCitationRefs(content, citationBox);
                scrollToBottom();
            },
            error(message, retry) {
                thinking.hidden = true;
                root.classList.add('failed');
                const line = el('div', 'msg-error');
                line.appendChild(el('span', null, message));
                if (retry) {
                    const b = el('button', 'ghost', '重试');
                    b.type = 'button';
                    b.addEventListener('click', () => {
                        b.disabled = true;
                        retry();
                    });
                    line.appendChild(b);
                }
                body.replaceChildren(line);
                scrollToBottom();
            },
            addPendingAction(action) {
                actions.appendChild(renderActionCard(action));
                scrollToBottom();
            },
            collapseSteps() {
                if (stepItems.size === 0) return;
                steps.hidden = true;
                toggle.hidden = false;
                toggle.textContent = `▸ 共 ${stepItems.size} 步`;
            }
        };
    }

    /** 把正文中的 [n] 变成可点击的链接，点击后定位并高亮下方对应的引用来源。 */
    function linkCitationRefs(content, citationBox) {
        if (!citationBox) return;
        const walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
        const textNodes = [];
        while (walker.nextNode()) textNodes.push(walker.currentNode);
        textNodes.forEach((node) => {
            if (!/\[\d{1,3}]/.test(node.nodeValue)) return;
            const fragment = document.createDocumentFragment();
            node.nodeValue.split(/(\[\d{1,3}])/).forEach((part) => {
                const m = /^\[(\d{1,3})]$/.exec(part);
                const target = m && citationBox.querySelector(`.citation[data-index="${m[1]}"]`);
                if (!target) {
                    fragment.appendChild(document.createTextNode(part));
                    return;
                }
                const link = el('a', 'cite-ref', part);
                link.href = '#';
                link.addEventListener('click', (e) => {
                    e.preventDefault();
                    citationBox.querySelectorAll('.citation.highlight').forEach((c) => c.classList.remove('highlight'));
                    target.classList.add('highlight');
                    target.scrollIntoView({behavior: 'smooth', block: 'nearest'});
                });
                fragment.appendChild(link);
            });
            node.replaceWith(fragment);
        });
    }

    // ---------- 待确认操作卡片（FR-021） ----------

    const ACTION_STATES = {
        PENDING: {label: '待确认', css: ''},
        EXECUTED: {label: '已执行', css: 'executed'},
        FAILED: {label: '执行失败', css: 'failed'},
        CANCELLED: {label: '已取消', css: 'cancelled'},
        EXPIRED: {label: '已作废（发送新消息后，未确认的操作自动作废）', css: 'expired'}
    };
    const ACTION_TYPES = {CREATE_TASK: '创建任务', UPDATE_TASK_STATUS: '修改任务状态'};

    function renderActionCard(action) {
        const card = el('div', 'action-card');
        card.dataset.actionId = action.id;
        updateActionCard(card, action);
        return card;
    }

    function updateActionCard(card, action) {
        const st = ACTION_STATES[action.status] || {label: action.status, css: ''};
        card.className = 'action-card' + (st.css ? ' ' + st.css : '');
        card.dataset.status = action.status;
        card.dataset.type = action.type || card.dataset.type || '';
        const label = el('div', 'label', `${ACTION_TYPES[action.type] || '操作'} · ${st.label}`);
        const summary = el('div', 'summary', action.summary);
        card.replaceChildren(label, summary);
        if (action.result && action.status !== 'PENDING' && action.status !== 'CANCELLED' && action.status !== 'EXPIRED') {
            card.appendChild(el('div', 'muted', action.result));
        }
        if (action.status === 'PENDING') {
            const buttons = el('div', 'buttons');
            const confirm = el('button', 'primary', '确认执行');
            const cancel = el('button', 'ghost', '取消');
            confirm.type = cancel.type = 'button';
            const resolve = async (verb) => {
                confirm.disabled = cancel.disabled = true;
                const res = await api(`/api/pending-actions/${action.id}/${verb}`, {method: 'POST'});
                if (res.ok) {
                    updateActionCard(card, await res.json());
                } else {
                    const msg = await errorMessage(res, '操作失败');
                    card.querySelector('.buttons')?.remove();
                    card.appendChild(el('div', 'form-error', msg));
                }
            };
            confirm.addEventListener('click', () => resolve('confirm'));
            cancel.addEventListener('click', () => resolve('cancel'));
            buttons.append(confirm, cancel);
            card.appendChild(buttons);
        }
    }

    function markActionsExpired(ids) {
        (ids || []).forEach((id) => {
            const card = document.querySelector(`.action-card[data-action-id="${id}"]`);
            if (card && card.dataset.status === 'PENDING') {
                updateActionCard(card, {
                    id, status: 'EXPIRED',
                    type: card.dataset.type,
                    summary: card.querySelector('.summary')?.textContent || ''
                });
            }
        });
    }

    function renderCitations(container, citations) {
        if (citations.length === 0) return null;
        const box = el('div', 'citations');
        box.appendChild(el('div', 'title', '引用来源'));
        citations.forEach((c) => {
            const item = el('div', 'citation');
            item.dataset.index = c.index;
            const source = el('div', 'source', `[${c.index}] 《${c.documentName}》` + (c.section ? ` · ${c.section}` : ''));
            item.append(source, el('div', 'excerpt', c.excerpt));
            box.appendChild(item);
        });
        container.appendChild(box);
        return box;
    }

    // ---------- 发送消息与事件流 ----------

    async function send(text) {
        if (state.busy || !text.trim()) return;
        state.busy = true;
        $('send').disabled = true;
        try {
            if (!state.currentId) {
                state.currentId = await createConversation();
                location.hash = `c=${state.currentId}`;
                renderConversationList();
            }
            appendUserMessage(text);
            const view = createAssistantMessage();
            const conversationId = state.currentId;
            const retry = () => {
                $('input').value = text;
                $('input').focus();
            };

            const res = await api(`/api/conversations/${conversationId}/messages`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({content: text})
            });
            if (!res.ok) {
                view.error(await errorMessage(res, '发送失败，请稍后重试'), retry);
                return;
            }
            await readEvents(res, (name, data) => handleEvent(view, name, data, retry));
            await loadConversations();
        } catch (e) {
            if (e.message !== 'unauthorized') {
                console.error(e);
            }
        } finally {
            state.busy = false;
            $('send').disabled = false;
        }
    }

    async function readEvents(res, onEvent) {
        const reader = res.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        for (;;) {
            const {value, done} = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, {stream: true});
            let index;
            while ((index = buffer.indexOf('\n\n')) >= 0) {
                const chunk = buffer.slice(0, index);
                buffer = buffer.slice(index + 2);
                let name = 'message';
                const data = [];
                chunk.split('\n').forEach((line) => {
                    if (line.startsWith('event:')) name = line.slice(6).trim();
                    else if (line.startsWith('data:')) data.push(line.slice(5));
                });
                onEvent(name, data.length ? JSON.parse(data.join('\n')) : {});
            }
        }
    }

    function handleEvent(view, name, data, retry) {
        switch (name) {
            case 'message_accepted':
                markActionsExpired(data.expiredActionIds);
                break;
            case 'pending_action':
                view.addPendingAction(data);
                break;
            case 'step_started':
                view.stepStarted(data);
                break;
            case 'step_finished':
                view.stepFinished(data);
                break;
            case 'answer':
                view.answer(data);
                view.collapseSteps();
                break;
            case 'error':
                view.error(data.message, retry);
                break;
            default:
                break;
        }
    }

    // ---------- 视图切换 ----------

    function showView(name) {
        $('chat-view').hidden = name !== 'chat';
        $('knowledge-view').hidden = name !== 'knowledge';
    }

    // ---------- 初始化 ----------

    function bindComposer() {
        const input = $('input');
        $('composer').addEventListener('submit', (e) => {
            e.preventDefault();
            const text = input.value.trim();
            if (!text) return;
            input.value = '';
            send(text);
        });
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
                e.preventDefault();
                $('composer').requestSubmit();
            }
        });
        input.addEventListener('input', () => {
            input.style.height = 'auto';
            input.style.height = Math.min(input.scrollHeight, 180) + 'px';
        });
    }

    async function init() {
        bindComposer();
        $('new-conversation').addEventListener('click', async () => {
            if (state.busy) return;
            const id = await createConversation();
            await openConversation(id);
        });
        $('logout').addEventListener('click', async () => {
            await api('/logout', {method: 'POST'});
            location.href = '/login.html?logout';
        });
        $('open-knowledge').addEventListener('click', () => window.AppKnowledge?.open());

        await loadMe();
        await loadConversations();
        const fromHash = /c=(\d+)/.exec(location.hash);
        await openConversation(fromHash ? Number(fromHash[1]) : null);
    }

    // ---------- 知识库视图（FR-006a） ----------

    const SOURCE_LABELS = {BUILT_IN: '内置', UPLOADED: '上传'};

    async function openKnowledge() {
        showView('knowledge');
        state.currentId = null;
        renderConversationList();
        const view = $('knowledge-view');
        view.replaceChildren();
        const root = el('div', 'kb');
        root.appendChild(el('h2', null, '知识库'));
        root.appendChild(el('p', 'muted', '助手回答公司制度相关问题时，会从这些文档中检索依据并标注出处。'));

        if (state.me.role === 'PROJECT_MANAGER') {
            const upload = el('div', 'upload');
            const input = el('input');
            input.type = 'file';
            input.accept = '.md,text/markdown';
            const button = el('button', 'primary', '上传 Markdown 文档');
            button.type = 'button';
            const result = el('span', 'upload-result');
            button.addEventListener('click', async () => {
                if (!input.files.length) {
                    result.className = 'upload-result error';
                    result.textContent = '请先选择 .md 文件';
                    return;
                }
                button.disabled = true;
                result.className = 'upload-result';
                result.textContent = '正在导入并向量化…';
                const form = new FormData();
                form.append('file', input.files[0]);
                const res = await api('/api/knowledge/documents', {method: 'POST', body: form});
                button.disabled = false;
                if (res.ok) {
                    const doc = await res.json();
                    result.className = 'upload-result ok';
                    result.textContent = `已导入《${doc.name}》，共 ${doc.chunkCount} 个片段，现在可以提问了`;
                    input.value = '';
                    await renderDocuments(table);
                } else {
                    result.className = 'upload-result error';
                    result.textContent = await errorMessage(res, '上传失败');
                }
            });
            upload.append(input, button, result);
            root.appendChild(upload);
            root.appendChild(el('p', 'muted', '同名文档会被新内容替换；仅支持 UTF-8 编码的 .md 文件，大小不超过 1 MB。'));
        }

        const table = el('table');
        root.appendChild(table);
        view.appendChild(root);
        await renderDocuments(table);
    }

    async function renderDocuments(table) {
        const res = await api('/api/knowledge/documents');
        const docs = await res.json();
        table.replaceChildren();
        const head = el('tr');
        ['文档名称', '来源', '片段数', '导入时间'].forEach((h) => head.appendChild(el('th', null, h)));
        table.appendChild(head);
        docs.forEach((d) => {
            const row = el('tr');
            row.append(el('td', null, d.name), el('td', null, SOURCE_LABELS[d.source] || d.source),
                el('td', null, String(d.chunkCount)), el('td', null, new Date(d.importedAt).toLocaleString('zh-CN')));
            table.appendChild(row);
        });
        if (docs.length === 0) {
            const row = el('tr');
            const cell = el('td', 'muted', '知识库为空');
            cell.colSpan = 4;
            row.appendChild(cell);
            table.appendChild(row);
        }
    }

    window.AppKnowledge = {open: openKnowledge};
    window.App = {api, errorMessage, el, showView, state, renderMarkdown};
    init().catch((e) => {
        if (e.message !== 'unauthorized') console.error(e);
    });
})();
