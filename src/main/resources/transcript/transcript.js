/*
 * Transcript document of the Sandbox Agent tool window.
 *
 * The host (see JcefTranscriptView) hands over batches of events and gets actions back:
 *
 *     window.transcript.apply(events)   host -> document
 *     window.transcriptHost(json)       document -> host (installed by the host)
 *
 * The protocol is documented in docs/development/TRANSCRIPT_WEBVIEW.md. Everything the document
 * needs to know about the IDE arrives as CSS custom properties (theme event), so this file has no
 * knowledge of the host it runs in — which is what makes the same document reusable elsewhere.
 *
 * Written as one classic script: the document is loaded from a string and therefore has an opaque
 * origin, where ES modules cannot be used.
 */
(function () {
    'use strict';

    var root = document.getElementById('transcript');
    var searchBar = document.getElementById('search');
    var searchInput = document.getElementById('search-input');
    var searchCount = document.getElementById('search-count');
    var scrollDownButton = document.getElementById('scroll-down');

    /** Entries by id: message blocks, tool call cards, groups and permission cards. */
    var entries = Object.create(null);
    /** Open tool call groups by id, with the state their `Working… 12s` label is built from. */
    var groups = Object.create(null);

    // ---------------------------------------------------------------- host bridge

    function send(action) {
        if (typeof window.transcriptHost === 'function') {
            window.transcriptHost(JSON.stringify(action));
        }
    }

    // ---------------------------------------------------------------- icons

    var SVG_NS = 'http://www.w3.org/2000/svg';
    /** The `ra-icon-…` symbols the host inlined; a name that is not among them draws `other`. */
    var ICON_PREFIX = 'ra-icon-';

    /**
     * One icon, as a reference to the sprite of the document.
     *
     * The drawings are svg files (icons/transcript/, see docs/development/ICONS.md) turned into
     * `<symbol>` elements by the host, so no geometry lives in this script and a transcript with
     * hundreds of tool call rows carries each drawing exactly once.
     */
    function icon(name) {
        var id = ICON_PREFIX + name;
        if (!name || !document.getElementById(id)) id = ICON_PREFIX + 'other';
        var svg = document.createElementNS(SVG_NS, 'svg');
        svg.setAttribute('class', 'icon');
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('focusable', 'false');
        var use = document.createElementNS(SVG_NS, 'use');
        use.setAttribute('href', '#' + id);
        svg.appendChild(use);
        return svg;
    }

    // ---------------------------------------------------------------- dom helpers

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }

    function iconButton(name, tooltip, onClick) {
        var button = el('button', 'icon-button');
        button.title = tooltip;
        button.appendChild(icon(name));
        button.addEventListener('click', function (event) {
            event.preventDefault();
            event.stopPropagation();
            onClick();
        });
        return button;
    }

    /** A quiet foldable row: the caret plus its text, with [body] shown while it is expanded. */
    function anchor(text, body, extraClass) {
        var button = el('button', 'anchor' + (extraClass ? ' ' + extraClass : ''));
        button.setAttribute('aria-expanded', 'false');
        button.appendChild(el('span', 'caret'));
        button.appendChild(el('span', 'anchor-text', text));
        // A row of a tool call is cut off instead of wrapped, so the full text lives in the tooltip.
        if (text) button.title = text;
        button.addEventListener('click', function () {
            keepInPlace(button, function () {
                var expanded = button.getAttribute('aria-expanded') === 'true';
                button.setAttribute('aria-expanded', expanded ? 'false' : 'true');
                body.hidden = expanded;
            });
        });
        return button;
    }

    function setAnchorText(button, text) {
        var label = button.querySelector('.anchor-text');
        if (label) label.textContent = text;
        button.title = text || '';
    }

    function expand(button, body, expanded) {
        button.setAttribute('aria-expanded', expanded ? 'true' : 'false');
        body.hidden = !expanded;
    }

    // ---------------------------------------------------------------- scrolling

    function scroller() {
        return document.scrollingElement || document.documentElement;
    }

    function atBottom() {
        var node = scroller();
        return node.scrollHeight - node.scrollTop - node.clientHeight < 24;
    }

    function scrollToBottom() {
        var node = scroller();
        node.scrollTop = node.scrollHeight;
        updateScrollDown();
    }

    /**
     * Shows the jump to the newest entry while there is something below the viewport.
     *
     * A transcript that fits on screen — and one that is read at its bottom edge, which is where a
     * running turn keeps it — has nothing to jump to, so the corner stays empty there.
     */
    function updateScrollDown() {
        if (!scrollDownButton) return;
        scrollDownButton.hidden = atBottom();
    }

    /**
     * Runs [change] keeping [node] exactly where it sits on screen.
     *
     * Unfolding a run of ten tool calls in the middle of the history must not throw the reader
     * down to the newest message, which is what pinning to the bottom would do.
     */
    function keepInPlace(node, change) {
        var before = node.getBoundingClientRect().top;
        change();
        var after = node.getBoundingClientRect().top;
        scroller().scrollTop += after - before;
    }

    // ---------------------------------------------------------------- markdown bodies

    /**
     * Fills [container] with sanitized HTML and turns the `data-choices` placeholders the host
     * left behind into check boxes with a `Send` action.
     */
    function fillMarkdown(container, html, choices, blockId) {
        container.innerHTML = html || '';
        addCodeCopy(container);
        (choices || []).forEach(function (group) {
            var slot = container.querySelector('[data-choices="' + group.slot + '"]');
            if (slot) renderChoices(slot, group.items, blockId);
        });
    }

    /**
     * Hangs a copy button over the bottom right corner of every code block.
     *
     * The button cannot sit inside the `pre`: that one scrolls horizontally, and an overlay in it
     * would drift away with the code. Each block therefore gets a wrapper that carries the button
     * and the margin, while the `pre` keeps the scrolling to itself.
     *
     * The text is handed to the host rather than written to the clipboard here — the document has
     * no clipboard permission, and the host is the one that owns the IDE's clipboard anyway. The
     * host answers with a `toast` once the text is in it, which is the feedback of the button.
     */
    function addCodeCopy(container) {
        Array.prototype.forEach.call(container.querySelectorAll('pre'), function (pre) {
            if (pre.classList.contains('stream')) return;
            var parent = pre.parentNode;
            if (!parent || (parent.classList && parent.classList.contains('code-block'))) return;
            var wrapper = el('div', 'code-block');
            parent.replaceChild(wrapper, pre);
            wrapper.appendChild(pre);
            var button = iconButton('copy', 'Copy', function () {
                send({type: 'copyText', text: pre.textContent});
            });
            button.classList.add('code-copy');
            wrapper.appendChild(button);
        });
    }

    function renderChoices(slot, items, blockId) {
        slot.textContent = '';
        var boxes = [];
        items.forEach(function (item) {
            var label = el('label', 'choice');
            var box = el('input');
            box.type = 'checkbox';
            box.checked = !!item.checked;
            label.appendChild(box);
            label.appendChild(el('span', null, item.label));
            slot.appendChild(label);
            boxes.push({box: box, label: item.label});
        });
        var actions = el('div', 'choices-actions');
        var send_ = el('button', 'button button--default', 'Send');
        send_.disabled = !boxes.some(function (entry) { return entry.box.checked; });
        boxes.forEach(function (entry) {
            entry.box.addEventListener('change', function () {
                send_.disabled = !boxes.some(function (other) { return other.box.checked; });
            });
        });
        send_.addEventListener('click', function () {
            var markdown = boxes.map(function (entry) {
                return '- [' + (entry.box.checked ? 'x' : ' ') + '] ' + entry.label;
            }).join('\n');
            send({type: 'submitChoices', id: blockId, markdown: markdown});
            boxes.forEach(function (entry) { entry.box.disabled = true; });
            actions.hidden = true;
        });
        actions.appendChild(send_);
        slot.appendChild(actions);
    }

    // ---------------------------------------------------------------- blocks

    var ROLES = {
        // No title: the user icon already says whose message it is.
        user: {icon: 'user', framed: true, copy: true},
        agent: {title: 'Agent', icon: 'agent', framed: true, copy: true},
        plan: {title: 'Plan', icon: 'plan', framed: true, quiet: true},
        thought: {fold: 'Thinking', quiet: true},
        system: {notice: true},
        warning: {notice: true, warning: true},
        completion: {notice: true},
        error: {notice: true, error: true}
    };

    function addBlock(event) {
        var role = ROLES[event.role] || ROLES.system;
        var existing = entries[event.id];
        if (existing) {
            root.removeChild(existing.node);
            delete entries[event.id];
        }
        var node = role.notice ? noticeBlock(event, role) : messageBlock(event, role);
        entries[event.id] = node;
        append(node.node);
    }

    function noticeBlock(event, role) {
        var node = el('div', 'entry notice' + (role.error ? ' notice--error' : '') + (role.warning ? ' notice--warning' : ''));
        if (role.error || role.warning) {
            var mark = icon('error');
            mark.classList.add('notice-icon');
            node.appendChild(mark);
        }
        var body = el('div', 'notice-body markdown');
        fillMarkdown(body, event.html, event.choices, event.id);
        node.appendChild(body);
        return {node: node, body: body};
    }

    /** A framed message (`You`, `Agent`, `Plan`) or the folded away `Thinking` of a turn. */
    function messageBlock(event, role) {
        var node = el('div', 'entry block block--' + event.role + (role.framed ? ' framed' : ''));
        var body = el('div', 'block-body markdown' + (role.quiet ? ' quiet' : ''));

        if (role.fold) {
            body.classList.add('fold-body');
            body.hidden = true;
            var fold = el('div', 'fold');
            fold.appendChild(anchor(role.fold, body));
            node.appendChild(fold);
        } else {
            node.appendChild(header(event, role));
        }

        if (event.streaming) {
            body.appendChild(el('pre', 'stream', ''));
        } else {
            fillMarkdown(body, event.html, event.choices, event.id);
        }
        node.appendChild(body);
        return {node: node, body: body, streaming: !!event.streaming};
    }

    function header(event, role) {
        var head = el('div', 'block-header');
        if (role.icon) {
            var mark = icon(role.icon);
            mark.classList.add('block-icon');
            head.appendChild(mark);
        }
        if (role.title) head.appendChild(el('span', 'block-title', role.title));
        if (role.copy) {
            var actions = el('div', 'block-actions');
            actions.appendChild(iconButton('copy', 'Copy', function () {
                send({type: 'copyBlock', id: event.id});
            }));
            head.appendChild(actions);
        }
        return head;
    }

    function appendStream(event) {
        var entry = entries[event.id];
        if (!entry) return;
        // After a Markdown rendering of the stream so far, the chunks go below it as plain text
        // until the next rendering replaces the whole body.
        var stream = entry.body.querySelector('pre.stream');
        if (!stream) {
            stream = el('pre', 'stream', '');
            entry.body.appendChild(stream);
        }
        stream.appendChild(document.createTextNode(event.text));
    }

    function replaceBlockHtml(event) {
        var entry = entries[event.id];
        if (!entry) return;
        fillMarkdown(entry.body, event.html, event.choices, event.id);
    }

    function addAttachments(event) {
        var entry = entries[event.id];
        if (!entry) return;
        var row = entry.node.querySelector('.chips');
        if (!row) {
            row = el('div', 'chips');
            entry.node.appendChild(row);
        }
        (event.chips || []).forEach(function (chip) {
            var node = el('span', 'chip', chip.label);
            if (chip.tooltip) node.title = chip.tooltip;
            node.addEventListener('click', function () {
                send({type: 'openAttachment', key: chip.key});
            });
            row.appendChild(node);
        });
    }

    /** Images of an agent answer, in a row of their own so a new body leaves them in place. */
    function addImages(event) {
        var entry = entries[event.id];
        if (!entry) return;
        var row = entry.node.querySelector('.images');
        if (!row) {
            row = el('div', 'images');
            entry.node.appendChild(row);
        }
        (event.sources || []).forEach(function (src) {
            // Only `data:` URIs of a raster type reach this point (TranscriptImages); the CSP
            // would refuse anything loaded from elsewhere.
            if (src.indexOf('data:image/') !== 0) return;
            var image = el('img', 'transcript-image');
            image.alt = event.alt || '';
            image.src = src;
            row.appendChild(image);
        });
    }

    /** The action closing a turn: it jumps back to the block the turn started with. */
    function markTurnEnd(event) {
        var entry = entries[event.id];
        if (!entry || entry.node.querySelector('.block-corner')) return;
        var corner = el('div', 'block-corner');
        corner.appendChild(iconButton('up', 'Scroll to the start of the turn', function () {
            var target = entries[event.targetId];
            if (target) {
                scroller().scrollTop += target.node.getBoundingClientRect().top;
            }
        }));
        entry.node.appendChild(corner);
    }

    // ---------------------------------------------------------------- tool calls

    function applyGroup(event) {
        var group = groups[event.id];
        if (!group) {
            var node = el('div', 'entry group');
            var body = el('div', 'group-body');
            var button = anchor('', body, 'group-anchor');
            // Spins next to `Working…` for as long as the group is open (see .spinner).
            var spinner = el('span', 'spinner');
            spinner.setAttribute('aria-hidden', 'true');
            button.insertBefore(spinner, button.querySelector('.anchor-text'));
            node.appendChild(button);
            node.appendChild(body);
            group = {node: node, body: body, anchor: button, timed: event.timed !== false, start: Date.now()};
            groups[event.id] = group;
            entries[event.id] = group;
            append(node);
            // An open group shows what is going on, so it starts unfolded and folds itself away
            // once the run is over.
            expand(button, body, true);
        }
        if (event.closed) {
            group.closed = true;
            group.elapsed = event.elapsedMs;
            if (group.timer) {
                window.clearInterval(group.timer);
                group.timer = null;
            }
            var spinner = group.anchor.querySelector('.spinner');
            if (spinner) spinner.remove();
            expand(group.anchor, group.body, false);
        } else if (group.timed && !group.timer) {
            group.timer = window.setInterval(function () { groupLabel(group); }, 3000);
        }
        groupLabel(group);
    }

    function groupLabel(group) {
        var seconds;
        if (group.closed) {
            seconds = group.elapsed == null ? null : Math.round(group.elapsed / 1000);
            setAnchorText(group.anchor, seconds ? 'Worked ' + seconds + 's' : 'Worked');
        } else if (group.timed) {
            seconds = Math.round((Date.now() - group.start) / 1000);
            setAnchorText(group.anchor, seconds ? 'Working… ' + seconds + 's' : 'Working…');
        } else {
            setAnchorText(group.anchor, 'Working…');
        }
    }

    function applyToolCall(event) {
        var existing = entries[event.id];
        var node = existing ? existing.node : el('div', 'toolcall');
        var expanded = existing ? existing.expanded : false;
        node.textContent = '';
        var card = event.switchMode ? switchModeCard(event) : toolCallCard(event, expanded);
        node.className = event.switchMode ? 'entry block block--switch-mode framed' : 'toolcall';
        node.appendChild(card.content);
        entries[event.id] = {node: node, expanded: card.expanded, body: card.body};
        if (existing) return;
        var group = event.groupId ? groups[event.groupId] : null;
        if (group) group.body.appendChild(node); else append(node);
    }

    /**
     * The questions an ask call put to the user, as they were shown: the header, the text, and the
     * options with their descriptions (a preview is folded away behind its own anchor). It is the
     * body of the folded row of the call and records what was asked and nothing else, so a restored
     * session still reads in full and the answer the agent goes on to give makes sense.
     */
    function askQuestions(question) {
        var list = el('div', 'ask-body');
        (question.questions || []).forEach(function (entry) {
            list.appendChild(askQuestion(entry));
        });
        return list;
    }

    function askQuestion(question) {
        var node = el('div', 'ask-question');
        if (question.header) node.appendChild(el('span', 'ask-chip', question.header));
        node.appendChild(questionText('div', 'ask-text markdown', question.questionHtml, question.question));
        node.appendChild(el('div', 'ask-hint', question.multiSelect ? 'Select one or more' : 'Select one'));
        var list = el('div', 'ask-options');
        (question.options || []).forEach(function (option) {
            var row = el('div', 'ask-option' + (question.multiSelect ? ' ask-option--multi' : ''));
            row.appendChild(el('span', 'ask-mark'));
            var text = el('div', 'ask-option-text');
            text.appendChild(questionText('div', 'ask-label', option.labelHtml, option.label));
            if (option.description) {
                text.appendChild(questionText('div', 'elicitation-description markdown', option.descriptionHtml, option.description));
            }
            if (option.preview) {
                var preview = questionText('div', 'elicitation-preview markdown', option.previewHtml, option.preview);
                preview.hidden = true;
                text.appendChild(anchor('Preview', preview));
                text.appendChild(preview);
            }
            row.appendChild(text);
            list.appendChild(row);
        });
        node.appendChild(list);
        return node;
    }

    function toolCallCard(event, expanded) {
        var content = document.createDocumentFragment();
        var line = el('div', 'toolcall-line');
        var mark = icon(event.kind);
        line.appendChild(mark);

        var body = el('div', 'toolcall-body markdown quiet');
        body.hidden = true;

        if (event.flat) {
            line.appendChild(el('span', 'prefix', event.flat.prefix));
            (event.flat.targets || []).forEach(function (target, index) {
                if (index > 0) line.appendChild(el('span', 'separator', event.flat.separator || '→'));
                line.appendChild(targetLink(event, target));
            });
            if (event.flat.expandable && event.bodyHtml) {
                var more = anchor('', body);
                line.appendChild(more);
                expand(more, body, expanded);
            }
        } else {
            var button = anchor(event.question ? event.question.headline : event.anchor, body);
            line.appendChild(button);
            expand(button, body, expanded);
        }
        if (event.statusLabel) {
            line.appendChild(el('span', 'status' + (event.failed ? ' notice--error' : ''), event.statusLabel));
        }
        if (event.question) {
            // What the call asked, as it was put; the raw input is not what a reader wants to see.
            body.appendChild(askQuestions(event.question));
        } else {
            fillMarkdown(body, event.bodyHtml, null, event.id);
        }
        content.appendChild(line);
        content.appendChild(body);
        return {content: content, body: body, expanded: !body.hidden};
    }

    function targetLink(event, target) {
        var node = el('span', 'target', target.text);
        node.title = target.path || target.url || target.text || '';
        node.addEventListener('click', function () {
            if (target.diffIndex != null) {
                send({type: 'openDiff', toolCallId: event.id, diffIndex: target.diffIndex});
            } else if (target.url) {
                send({type: 'openUrl', url: target.url});
            } else if (target.path) {
                send({type: 'openFile', path: target.path, line: target.line == null ? null : target.line});
            }
        });
        return node;
    }

    /**
     * A `switch_mode` changes what the agent may do for the rest of the session, so it is the one
     * tool call that keeps a framed block of its own with its plan spelled out.
     */
    function switchModeCard(event) {
        var content = document.createDocumentFragment();
        var head = el('div', 'block-header');
        var mark = icon('switch_mode');
        mark.classList.add('block-icon');
        head.appendChild(mark);
        head.appendChild(el('span', 'block-title', event.switchMode.headline +
            (event.switchMode.modeId ? '  →  ' + event.switchMode.modeId : '')));
        if (event.statusLabel) head.appendChild(el('span', 'status', event.statusLabel));
        var body = el('div', 'block-body markdown');
        fillMarkdown(body, event.switchMode.planHtml, null, event.id);
        content.appendChild(head);
        content.appendChild(body);
        return {content: content, body: body, expanded: true};
    }

    // ---------------------------------------------------------------- permission

    function addPermission(event) {
        var node = el('div', 'entry block block--permission framed');
        node.appendChild(el('div', 'permission-headline', event.headline));

        var body = el('div', 'permission-body markdown quiet');
        fillMarkdown(body, event.bodyHtml, null, event.id);
        if (event.bodyHtml) {
            if (event.expanded) {
                node.appendChild(body);
            } else {
                body.hidden = true;
                body.classList.add('fold-body');
                var fold = el('div', 'fold');
                fold.appendChild(anchor('Details', body));
                node.appendChild(fold);
                node.appendChild(body);
            }
        }
        if (event.flat) {
            var line = el('div', 'toolcall-line');
            line.appendChild(el('span', 'prefix', event.flat.prefix));
            (event.flat.targets || []).forEach(function (target, index) {
                if (index > 0) line.appendChild(el('span', 'separator', event.flat.separator || '→'));
                // A diff belongs to the tool call behind the request, not to the request itself.
                line.appendChild(targetLink({id: event.toolCallId || event.id}, target));
            });
            node.appendChild(line);
        }

        var actions = el('div', 'permission-actions');
        var status = el('div', 'permission-status');
        status.hidden = true;
        (event.options || []).forEach(function (option) {
            var button = el('button', 'button' + (option['default'] || option.allow ? ' button--default' : ''), option.label);
            button.addEventListener('click', function () {
                send({type: 'permission', id: event.id, optionId: option.optionId});
                lock(actions, status, option.label);
            });
            actions.appendChild(button);
        });
        node.appendChild(actions);
        node.appendChild(status);
        entries[event.id] = {node: node, body: body, actions: actions, status: status};
        append(node);
    }

    function lock(actions, status, text) {
        Array.prototype.forEach.call(actions.querySelectorAll('button'), function (button) {
            button.disabled = true;
        });
        status.textContent = text;
        status.hidden = false;
    }

    function resolvePermission(event) {
        var entry = entries[event.id];
        if (!entry || !entry.actions) return;
        lock(entry.actions, entry.status, event.status);
    }

    // ---------------------------------------------------------------- elicitation

    /**
     * Text the agent asks the user with, which is Markdown. The host renders and sanitizes it
     * (`TranscriptHtml.renderQuestion`, links already reduced to their text), so it is set as HTML;
     * a host that sends no HTML gets the plain text instead.
     */
    function questionText(tag, className, html, text) {
        var node = el(tag, className);
        if (html) node.innerHTML = html; else node.textContent = text || '';
        return node;
    }

    /** Selects with more options than this are drawn as a drop down instead of a column of radios. */
    var MAX_RADIOS = 6;
    var EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

    /**
     * The form of an `elicitation/create`: the agent asks for structured input, and the card says
     * who asks before it shows what.
     *
     * The answer is checked here before it is sent, so the user sees what is wrong and can fix it;
     * the host checks it once more against the schema it kept (see `ElicitationForm.coerce`). An
     * optional field left empty is not sent at all, which is how a question that was skipped is
     * told apart from one answered with an empty string.
     */
    function addElicitation(event) {
        var node = el('div', 'entry block block--permission framed');
        node.appendChild(el('div', 'elicitation-source', ROLES.agent.title + ' asks'));
        if (event.message) node.appendChild(questionText('div', 'permission-headline markdown', event.messageHtml, event.message));

        var form = el('form', 'elicitation-form');
        form.noValidate = true;
        var previousKey = null;
        var controls = (event.fields || []).map(function (field) {
            var control = buildField(form, event.id, field, previousKey);
            previousKey = field.key;
            return control;
        });

        var actions = el('div', 'permission-actions');
        var status = el('div', 'permission-status');
        status.hidden = true;
        var submit = el('button', 'button button--default', 'Submit');
        submit.type = 'submit';
        var decline = el('button', 'button', 'Decline');
        decline.type = 'button';
        var cancel = el('button', 'button', 'Cancel');
        cancel.type = 'button';
        actions.appendChild(submit);
        actions.appendChild(decline);
        actions.appendChild(cancel);
        form.appendChild(actions);
        node.appendChild(form);
        node.appendChild(status);

        function answer(action, content, label) {
            send({type: 'elicitation', id: event.id, action: action, content: content});
            lockElicitation(entry, label);
        }
        form.addEventListener('submit', function (submitEvent) {
            submitEvent.preventDefault();
            var content = {};
            var firstError = null;
            controls.forEach(function (control) {
                var result = control.read();
                control.showError(result.error);
                if (result.error && !firstError) firstError = control;
                if (!result.error && !result.empty) content[control.field.key] = result.value;
            });
            if (firstError) {
                firstError.focus();
                return;
            }
            answer('accept', content, 'Submitted');
        });
        decline.addEventListener('click', function () { answer('decline', null, 'Declined'); });
        cancel.addEventListener('click', function () { answer('cancel', null, 'Cancelled'); });

        var entry = {node: node, form: form, status: status};
        entries[event.id] = entry;
        append(node);
    }

    /**
     * One field: its label, its control and the line an error is shown in; see [checkField].
     *
     * Fields are told apart by a dotted rule, which is what keeps the questions of an
     * `AskUserQuestion` apart. The free text "Other" field the adapter adds after each of them
     * (`<key>_custom`) belongs to the question above it and is not separated from it.
     */
    function buildField(form, requestId, field, previousKey) {
        var row = el('div', 'elicitation-field' +
            (previousKey && field.key === previousKey + '_custom' ? ' elicitation-field--attached' : ''));
        var error = el('div', 'elicitation-error');
        error.hidden = true;
        var read;
        var focus;
        /** The title of the field, then the mark of a required one. */
        function title(tag, className) {
            var node = questionText(tag, className, field.titleHtml, field.title);
            if (field.required) node.appendChild(document.createTextNode(' *'));
            return node;
        }

        if (field.kind === 'boolean') {
            var label = el('label', 'choice');
            var box = el('input');
            box.type = 'checkbox';
            box.checked = field['default'] === true;
            label.appendChild(box);
            label.appendChild(title('span', null));
            row.appendChild(label);
            read = function () { return {value: box.checked}; };
            focus = function () { box.focus(); };
        } else {
            row.appendChild(title('div', 'elicitation-label'));
        }
        if (field.description) {
            row.appendChild(questionText('div', 'elicitation-description markdown', field.descriptionHtml, field.description));
        }

        if (field.kind === 'text' || field.kind === 'number') {
            var input = el('input', 'elicitation-input');
            input.type = field.kind === 'number' ? 'number' : 'text';
            if (field.kind === 'number') input.step = field.integer ? '1' : 'any';
            if (field['default'] != null) input.value = String(field['default']);
            row.appendChild(input);
            read = function () { return checkField(field, input.value); };
            focus = function () { input.focus(); };
        } else if (field.kind === 'choice') {
            var picked = field['default'];
            if ((field.options || []).length > MAX_RADIOS) {
                var select = el('select', 'elicitation-input');
                if (!field.required) select.appendChild(el('option', null, ''));
                field.options.forEach(function (option) {
                    var item = el('option', null, option.title);
                    item.value = option.value;
                    item.selected = option.value === picked;
                    select.appendChild(item);
                });
                row.appendChild(select);
                var selectPreview = previewBox(row, field.options);
                select.addEventListener('change', function () { selectPreview.show(select.value); });
                selectPreview.show(picked);
                read = function () { return checkField(field, select.value); };
                focus = function () { select.focus(); };
            } else {
                var radios = [];
                var group = el('div', 'choices');
                field.options.forEach(function (option) {
                    var radio = el('input');
                    radio.type = 'radio';
                    radio.name = requestId + ':' + field.key;
                    radio.value = option.value;
                    radio.checked = option.value === picked;
                    radios.push(radio);
                    group.appendChild(optionLabel(radio, option));
                });
                row.appendChild(group);
                var radioPreview = previewBox(row, field.options);
                radios.forEach(function (radio) {
                    radio.addEventListener('change', function () { radioPreview.show(radio.value); });
                });
                radioPreview.show(picked);
                if (!field.required) {
                    var clear = el('button', 'elicitation-clear', 'Clear');
                    clear.type = 'button';
                    clear.addEventListener('click', function () {
                        radios.forEach(function (radio) { radio.checked = false; });
                        radioPreview.show(null);
                    });
                    row.appendChild(clear);
                }
                read = function () {
                    var checked = radios.filter(function (radio) { return radio.checked; })[0];
                    return checkField(field, checked ? checked.value : '');
                };
                focus = function () { if (radios[0]) radios[0].focus(); };
            }
        } else if (field.kind === 'multi') {
            var defaults = field['default'] || [];
            var boxes = [];
            var list = el('div', 'choices');
            field.options.forEach(function (option) {
                var checkbox = el('input');
                checkbox.type = 'checkbox';
                checkbox.value = option.value;
                checkbox.checked = defaults.indexOf(option.value) >= 0;
                boxes.push(checkbox);
                list.appendChild(optionLabel(checkbox, option));
            });
            row.appendChild(list);
            read = function () {
                return checkField(field, boxes.filter(function (checkbox) { return checkbox.checked; })
                    .map(function (checkbox) { return checkbox.value; }));
            };
            focus = function () { if (boxes[0]) boxes[0].focus(); };
        }

        row.appendChild(error);
        form.appendChild(row);
        return {
            field: field,
            read: read || function () { return {empty: true}; },
            focus: focus || function () {},
            showError: function (text) {
                error.textContent = text || '';
                error.hidden = !text;
            }
        };
    }

    /**
     * The monospace box under a single select that shows the `preview` of the selected option
     * (a code snippet or a mockup the agent attached). The preview is Markdown, rendered by the
     * host, and the box keeps the whitespace of its lines, so a mockup keeps its shape. It stays
     * hidden while the selected option has none.
     */
    function previewBox(row, options) {
        var box = el('div', 'elicitation-preview markdown');
        box.hidden = true;
        row.appendChild(box);
        return {
            show: function (value) {
                var match = (options || []).filter(function (option) { return option.value === value; })[0];
                var preview = match && match.preview;
                box.textContent = '';
                if (preview) {
                    if (match.previewHtml) box.innerHTML = match.previewHtml; else box.textContent = preview;
                }
                box.hidden = !preview;
            }
        };
    }

    /** A radio button or a check box with the title of its option and, quieter, its description. */
    function optionLabel(input, option) {
        var label = el('label', 'choice');
        label.appendChild(input);
        var text = el('span', 'choice-text');
        text.appendChild(questionText('span', null, option.titleHtml, option.title));
        if (option.description) {
            text.appendChild(questionText('div', 'elicitation-description markdown', option.descriptionHtml, option.description));
        }
        label.appendChild(text);
        return label;
    }

    /**
     * Checks [raw] against the constraints of [field] and returns `{value}` for an answer,
     * `{empty: true}` for a field left empty and `{error}` for one that does not fit.
     */
    function checkField(field, raw) {
        var kind = field.kind;
        if (kind === 'boolean') return {value: !!raw};
        var empty = kind === 'multi' ? raw.length === 0 : raw === '' || raw == null;
        // Constraints only bind a value that is there: an optional field left empty is skipped.
        if (empty) return field.required ? {error: 'This field is required.'} : {empty: true};
        if (kind === 'text') {
            if (field.minLength != null && raw.length < field.minLength) return {error: 'At least ' + field.minLength + ' characters.'};
            if (field.maxLength != null && raw.length > field.maxLength) return {error: 'At most ' + field.maxLength + ' characters.'};
            if (!matchesFormat(field.format, raw)) return {error: 'Not a valid ' + field.format + '.'};
            if (field.pattern) {
                var regex = null;
                try { regex = new RegExp(field.pattern); } catch (ignored) { /* a pattern this engine cannot read is not held against the user */ }
                if (regex && !regex.test(raw)) return {error: 'Does not match the expected pattern.'};
            }
            return {value: raw};
        }
        if (kind === 'number') {
            var number = Number(raw);
            if (!isFinite(number)) return {error: 'Not a number.'};
            if (field.integer && Math.floor(number) !== number) return {error: 'Must be a whole number.'};
            if (field.minimum != null && number < field.minimum) return {error: 'At least ' + field.minimum + '.'};
            if (field.maximum != null && number > field.maximum) return {error: 'At most ' + field.maximum + '.'};
            return {value: number};
        }
        if (kind === 'multi') {
            if (field.minItems != null && raw.length < field.minItems) return {error: 'Choose at least ' + field.minItems + '.'};
            if (field.maxItems != null && raw.length > field.maxItems) return {error: 'Choose at most ' + field.maxItems + '.'};
        }
        return {value: raw};
    }

    function matchesFormat(format, text) {
        if (format === 'email') return EMAIL.test(text);
        if (format === 'uri') {
            try { new URL(text); return true; } catch (ignored) { return false; }
        }
        if (format === 'date') return /^\d{4}-\d{2}-\d{2}$/.test(text) && !isNaN(Date.parse(text));
        if (format === 'date-time') return /(Z|[+-]\d{2}:\d{2})$/.test(text) && !isNaN(Date.parse(text));
        return true;
    }

    function lockElicitation(entry, text) {
        Array.prototype.forEach.call(entry.form.querySelectorAll('input, select, button'), function (control) {
            control.disabled = true;
        });
        entry.status.textContent = text;
        entry.status.hidden = false;
    }

    function resolveElicitation(event) {
        var entry = entries[event.id];
        if (!entry || !entry.form) return;
        lockElicitation(entry, event.status);
    }

    // ---------------------------------------------------------------- search

    var searchState = {query: '', hits: [], current: -1, timer: null};

    /**
     * The bar sits on the bottom edge of the viewport (see transcript.css), which would cover the
     * last entries: the body is given the height of the bar as bottom padding while it is open.
     */
    function reserveSearchSpace(open) {
        document.body.classList.toggle('search-open', open);
        if (open) {
            document.documentElement.style.setProperty(
                '--ra-search-height', searchBar.offsetHeight + 'px');
        }
    }

    function showSearch() {
        searchBar.hidden = false;
        reserveSearchSpace(true);
        searchInput.focus();
        searchInput.select();
    }

    function closeSearch() {
        searchBar.hidden = true;
        reserveSearchSpace(false);
        clearHits();
        searchState.query = '';
        searchCount.textContent = '';
    }

    function clearHits() {
        Array.prototype.forEach.call(root.querySelectorAll('mark.search-hit'), function (hit) {
            var parent = hit.parentNode;
            parent.replaceChild(document.createTextNode(hit.textContent), hit);
            parent.normalize();
        });
        searchState.hits = [];
        searchState.current = -1;
    }

    /**
     * Own implementation instead of the browser's find: the text nodes are walked and the matches
     * wrapped in `mark` elements, which is what lets the current hit be highlighted differently
     * and counted in the search bar.
     */
    function runSearch(query) {
        clearHits();
        searchState.query = query;
        if (!query) {
            searchCount.textContent = '';
            return;
        }
        var needle = query.toLowerCase();
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: function (node) {
                if (!node.nodeValue || !node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
                var parent = node.parentNode;
                if (parent && parent.closest('[hidden]')) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
            }
        });
        var texts = [];
        var node;
        while ((node = walker.nextNode())) texts.push(node);
        texts.forEach(function (text) { markHits(text, needle); });
        if (searchState.hits.length > 0) moveSearch(0); else searchCount.textContent = '0/0';
    }

    /** Wraps every occurrence of [needle] inside one text node, walking the remainder as it goes. */
    function markHits(text, needle) {
        var node = text;
        while (node && node.nodeValue) {
            var at = node.nodeValue.toLowerCase().indexOf(needle);
            if (at < 0) return;
            var hit = node.splitText(at);
            var rest = hit.splitText(needle.length);
            var mark = el('mark', 'search-hit');
            hit.parentNode.replaceChild(mark, hit);
            mark.appendChild(hit);
            searchState.hits.push(mark);
            node = rest;
        }
    }

    function moveSearch(step) {
        var total = searchState.hits.length;
        if (total === 0) return;
        var previous = searchState.hits[searchState.current];
        if (previous) previous.classList.remove('search-hit--current');
        searchState.current = ((searchState.current + step) % total + total) % total;
        var hit = searchState.hits[searchState.current];
        hit.classList.add('search-hit--current');
        hit.scrollIntoView({block: 'center'});
        searchCount.textContent = (searchState.current + 1) + '/' + total;
    }

    searchInput.addEventListener('input', function () {
        window.clearTimeout(searchState.timer);
        var query = searchInput.value;
        searchState.timer = window.setTimeout(function () { runSearch(query); }, 150);
    });
    searchInput.addEventListener('keydown', function (event) {
        if (event.key === 'Enter') {
            event.preventDefault();
            moveSearch(event.shiftKey ? -1 : 1);
        } else if (event.key === 'Escape') {
            closeSearch();
        }
    });
    searchBar.addEventListener('click', function (event) {
        var action = event.target.getAttribute && event.target.getAttribute('data-search');
        if (action === 'next') moveSearch(1);
        else if (action === 'prev') moveSearch(-1);
        else if (action === 'close') closeSearch();
    });
    document.addEventListener('keydown', function (event) {
        if ((event.ctrlKey || event.metaKey) && event.key === 'f') {
            event.preventDefault();
            showSearch();
        } else if (event.key === 'Escape' && !searchBar.hidden) {
            closeSearch();
        }
    });

    // ---------------------------------------------------------------- scroll to bottom

    if (scrollDownButton) {
        scrollDownButton.appendChild(icon('down'));
        scrollDownButton.addEventListener('click', function (event) {
            event.preventDefault();
            scrollToBottom();
        });
    }
    // The document scrolls as a whole, so the scroll of the window is the scroll of the transcript.
    window.addEventListener('scroll', updateScrollDown);
    window.addEventListener('resize', updateScrollDown);

    // ---------------------------------------------------------------- toast

    var toastNode = document.getElementById('toast');
    var toastTimer = null;

    /**
     * Shows a message of the host over the bottom edge for a moment.
     *
     * It is the confirmation of the copy buttons above all: the clipboard is written by the host,
     * so the message only arrives once the text really is in it.
     */
    function showToast(event) {
        var text = (event && event.text) || '';
        if (!text || !toastNode) return;
        toastNode.textContent = text;
        toastNode.hidden = false;
        // A second copy has to restart the fade, which needs the class off for one layout pass.
        toastNode.classList.remove('toast--shown');
        void toastNode.offsetWidth;
        toastNode.classList.add('toast--shown');
        window.clearTimeout(toastTimer);
        toastTimer = window.setTimeout(function () {
            toastNode.classList.remove('toast--shown');
        }, 1600);
    }

    // ---------------------------------------------------------------- agent

    /**
     * Applies who the agent is: the name its blocks are headed with, and the drawing next to it.
     *
     * Both come from the settings, so both can change while a transcript is on screen. The name is
     * written into the headers that are already there, and the drawing replaces the `agent` symbol
     * of the sprite — every `<use>` of it follows, so nothing has to be rebuilt.
     */
    function applyAgent(event) {
        var name = event && event.name;
        if (name) {
            ROLES.agent.title = name;
            var titles = root.querySelectorAll('.block--agent .block-title');
            for (var index = 0; index < titles.length; index++) titles[index].textContent = name;
        }
        if (event && event.iconSymbol) replaceSymbol(event.iconSymbol);
    }

    /** Puts [markup], a whole `<symbol>`, in the place of the one of the sprite carrying its id. */
    function replaceSymbol(markup) {
        var holder = document.createElementNS(SVG_NS, 'svg');
        holder.innerHTML = markup;
        var symbol = holder.firstElementChild;
        if (!symbol || !symbol.id) return;
        var current = document.getElementById(symbol.id);
        if (!current || !current.parentNode) return;
        current.parentNode.replaceChild(symbol, current);
    }

    // ---------------------------------------------------------------- theme

    function applyTheme(event) {
        var style = document.documentElement.style;
        Object.keys(event.tokens || {}).forEach(function (name) {
            style.setProperty(name, event.tokens[name]);
        });
        document.documentElement.setAttribute('data-theme', event.dark ? 'dark' : 'light');
    }

    // ---------------------------------------------------------------- event loop

    function append(node) {
        root.appendChild(node);
    }

    function reset() {
        Object.keys(groups).forEach(function (id) {
            if (groups[id].timer) window.clearInterval(groups[id].timer);
        });
        entries = Object.create(null);
        groups = Object.create(null);
        root.textContent = '';
        closeSearch();
    }

    var HANDLERS = {
        reset: reset,
        theme: applyTheme,
        agent: applyAgent,
        block: addBlock,
        stream: appendStream,
        blockHtml: replaceBlockHtml,
        attachments: addAttachments,
        images: addImages,
        group: applyGroup,
        toolCall: applyToolCall,
        permission: addPermission,
        permissionResolved: resolvePermission,
        elicitation: addElicitation,
        elicitationResolved: resolveElicitation,
        turnEnd: markTurnEnd,
        toast: showToast
    };

    window.transcript = {
        /**
         * Applies a batch of events. The transcript is only pinned to its bottom edge when it was
         * already there: a reader who scrolled up stays where they are.
         */
        apply: function (events) {
            var pinned = atBottom();
            (events || []).forEach(function (event) {
                var handler = HANDLERS[event.type];
                if (!handler) return;
                try {
                    handler(event);
                } catch (error) {
                    if (window.console) window.console.error('transcript', event.type, error);
                }
            });
            if (pinned) scrollToBottom();
            if (searchState.query) runSearch(searchState.query);
            updateScrollDown();
        },
        showSearch: showSearch,
        /**
         * Jumps to the newest entry, which is what the host asks for when a prompt is sent: the
         * answer belongs at the bottom edge, no matter where the reader had scrolled to.
         */
        scrollToBottom: scrollToBottom,
        reset: reset
    };

    send({type: 'ready'});
})();
