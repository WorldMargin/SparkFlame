// 文案脚本编辑器（Monaco）：Kotlin 侧通过 window.spark 读写文本与类型声明。
// 页面自身不发起任何网络请求，全部资源来自模块 assets。
(function () {
    'use strict';

    // vs 目录用**完整的 https 地址**：
    // 1) AMD loader 把相对路径按站点根解析，写相对值会让模块与 worker 全部 404；
    // 2) worker 运行在 blob 源里，只有带 scheme 的完整地址才能被 fetch / importScripts 解析。
    var ORIGIN = 'https://appassets.androidplatform.net/assets/monaco';
    require.config({ paths: { vs: ORIGIN + '/vs' } });

    // 不覆盖 MonacoEnvironment.getWorkerUrl：Monaco 默认的 worker 工厂会用 require.toUrl
    // 生成 worker 引导地址（worker 名即 label），语言服务的 worker 才能正常起来。

    function post(name, payload) {
        var api = window.AndroidEditor;
        if (!api || typeof api[name] !== 'function') {
            return;
        }
        // 桥接方法按 Kotlin 侧签名调用：无参的不许多传参数，否则报 Method not found
        if (typeof payload === 'undefined') {
            api[name]();
        } else {
            api[name](payload);
        }
    }

    require(['vs/editor/editor.main'], function () {
        var MIN_FONT = 10;
        var MAX_FONT = 26;
        var DEFAULT_FONT = 14;
        var fontSize = parseInt(localStorage.getItem('spark.fontSize') || '', 10);
        if (!fontSize || fontSize < MIN_FONT || fontSize > MAX_FONT) {
            fontSize = DEFAULT_FONT;
        }

        var editor = monaco.editor.create(document.getElementById('editor'), {
            value: '',
            language: 'javascript',
            theme: 'vs',
            fontSize: fontSize,
            lineHeight: 0,
            fontFamily: 'Menlo, Consolas, "Droid Sans Mono", "Noto Sans Mono CJK SC", monospace, "Noto Color Emoji"',
            fontLigatures: false,
            lineNumbers: 'on',
            lineNumbersMinChars: 2,
            glyphMargin: false,
            folding: false,
            minimap: { enabled: false },
            // 不自动换行：代码按行横向滚动（换行会把长行拆得七零八落，很难看）
            wordWrap: 'off',
            wrappingIndent: 'none',
            scrollBeyondLastLine: false,
            automaticLayout: true,
            tabSize: 2,
            insertSpaces: true,
            renderLineHighlight: 'none',
            contextmenu: true,
            quickSuggestions: { other: true, comments: false, strings: false },
            suggestOnTriggerCharacters: true,
            tabCompletion: 'on',
            snippetSuggestions: 'inline',
            fixedOverflowWidgets: true,
            padding: { top: 10, bottom: 80 },
            scrollbar: {
                verticalScrollbarSize: 8,
                horizontalScrollbarSize: 8,
                horizontal: 'auto',
                alwaysConsumeMouseWheel: false
            }
        });

        monaco.languages.typescript.javascriptDefaults.setCompilerOptions({
            target: monaco.languages.typescript.ScriptTarget.ES2020,
            allowNonTsExtensions: true,
            allowJs: true,
            checkJs: true
        });
        monaco.languages.typescript.javascriptDefaults.setDiagnosticsOptions({
            noSemanticValidation: false,
            noSyntaxValidation: false
        });
        monaco.languages.typescript.javascriptDefaults.setEagerModelSync(true);

        var userLib = null;
        var changeTimer = null;

        window.spark = {
            setText: function (text) {
                editor.setValue(text || '');
            },
            getText: function () {
                return editor.getValue();
            },
            /**
             * 在光标处插入文本（「粘贴」按钮用）。
             *
             * 长按的原生选择菜单已被宿主关掉，粘贴只能走这里：把剪贴板内容插到当前选区/光标处。
             */
            insertText: function (text) {
                if (!text) {
                    return;
                }
                var selection = editor.getSelection();
                editor.executeEdits('spark-paste', [{ range: selection, text: text, forceMoveMarkers: true }]);
                editor.pushUndoStop();
                editor.focus();
            },
            setTypes: function (dts) {
                if (userLib) {
                    userLib.dispose();
                }
                userLib = monaco.languages.typescript.javascriptDefaults.addExtraLib(dts || '', 'spark.d.ts');
            },
            setDark: function (dark) {
                monaco.editor.setTheme(dark ? 'vs-dark' : 'vs');
            },
            focus: function () {
                editor.focus();
            },
            triggerSuggest: function () {
                editor.focus();
                editor.trigger('keyboard', 'editor.action.triggerSuggest', {});
            },
            showHover: function () {
                editor.trigger('keyboard', 'editor.action.showHover', {});
            },
            /**
             * 快速格式化：先清掉从 AI/网页复制常见的脏东西（零宽字符、不换行空格、
             * 智能引号、Markdown 代码围栏），再用语言服务整篇格式化，
             * 最后按括号层级重排缩进（语言服务没提供格式化时的兜底，也顺手统一 4 空格）。
             */
            format: function () {
                var text = editor.getValue();
                var cleaned = text
                    .replace(/^\s*```[a-zA-Z]*\s*\n?/, '')
                    .replace(/\n?```\s*$/, '')
                    .replace(/[\u200B-\u200D\u2060\uFEFF]/g, '')
                    .replace(/\u00A0/g, ' ')
                    .replace(/[\u2018\u2019]/g, "'")
                    .replace(/[\u201C\u201D]/g, '"');
                if (cleaned !== text) {
                    editor.setValue(cleaned);
                }
                var action = editor.getAction('editor.action.formatDocument');
                if (action) {
                    action.run();
                }
                setTimeout(function () {
                    var current = editor.getValue();
                    var tidy = reindent(current);
                    if (tidy !== current) {
                        editor.setValue(tidy);
                    }
                }, 300);
                return cleaned !== text;
            },
            /** 直接设置字号（双指缩放走同一条路径），并记住它。 */
            setFontSize: function (size) {
                var next = Math.max(MIN_FONT, Math.min(MAX_FONT, Math.round(size)));
                if (next === fontSize) {
                    return fontSize;
                }
                fontSize = next;
                editor.updateOptions({ fontSize: fontSize });
                localStorage.setItem('spark.fontSize', String(fontSize));
                return fontSize;
            },
            fontSize: function () {
                return fontSize;
            }
        };

        /** 按括号层级重排缩进（不动换行、不动内容，只改行首空白）。 */
        function reindent(text) {
            var lines = text.replace(/\r\n?/g, '\n').split('\n');
            var out = [];
            var depth = 0;
            for (var i = 0; i < lines.length; i++) {
                var line = lines[i].replace(/\s+$/, '');
                var trimmed = line.replace(/^\s+/, '');
                if (trimmed === '') {
                    out.push('');
                    continue;
                }
                var leadingClose = (trimmed.match(/^[)\]}]+/) || [''])[0].length;
                var indent = Math.max(0, depth - leadingClose);
                out.push(new Array(indent + 1).join('    ') + trimmed);
                depth = Math.max(0, depth + netBrackets(trimmed));
            }
            return out.join('\n');
        }

        /** 一行里括号的净增量（粗略：字符串里的括号也会算进来，作为轻量格式化够用）。 */
        function netBrackets(line) {
            var delta = 0;
            for (var i = 0; i < line.length; i++) {
                var ch = line.charAt(i);
                if (ch === '{' || ch === '(' || ch === '[') {
                    delta++;
                } else if (ch === '}' || ch === ')' || ch === ']') {
                    delta--;
                }
            }
            return delta;
        }

        // 每次内容变化立刻回传：脚本很短，过桥开销可忽略；
        // 之前用防抖 + 滚动时取消，会在「打字后滚动一下再保存」时丢掉最后一次更新。
        editor.onDidChangeModelContent(function () {
            post('onChange', editor.getValue());
        });

        // 键盘只在**明确点一下编辑区**时弹：
        // Monaco 在手指按下时就抢焦点，直接监听焦点会导致「滑动也弹键盘」，
        // 所以这里自己判断触摸手势——有位移（滑动）或长按都不弹。
        var touchStart = null;
        var pinchStart = null;
        var editorDom = document.getElementById('editor');

        // 触摸事件用捕获阶段：Monaco 自己的手势处理会在冒泡阶段 stopPropagation，
        // 挂在冒泡上的 listener 收不到 touchmove，滑动就会被误判成点击、弹出键盘。
        var CAPTURE = { passive: true, capture: true };

        /** 摘掉 Monaco 输入节点的焦点并让宿主收起输入法（滑动时用）。 */
        function blurEditor() {
            try {
                var node = editor.getDomNode && editor.getDomNode();
                var area = node && node.querySelector('textarea');
                if (area && document.activeElement === area) {
                    area.blur();
                }
                if (document.activeElement && document.activeElement.blur) {
                    document.activeElement.blur();
                }
            } catch (e) {
                // 拿不到节点就算了，宿主仍会收起输入法
            }
            post('onBlur');
        }

        function touchDistance(event) {
            var a = event.touches[0];
            var b = event.touches[1];
            return Math.sqrt((a.clientX - b.clientX) * (a.clientX - b.clientX) + (a.clientY - b.clientY) * (a.clientY - b.clientY));
        }

        editorDom.addEventListener('touchstart', function (event) {
            if (event.touches.length === 2) {
                // 双指：进入缩放，不当作点击
                touchStart = null;
                pinchStart = { distance: touchDistance(event), fontSize: fontSize };
                return;
            }
            if (event.touches.length !== 1) {
                return;
            }
            var touch = event.touches[0];
            touchStart = {
                x: touch.clientX,
                y: touch.clientY,
                at: Date.now(),
                moved: false,
                scrollLeft: editor.getScrollLeft(),
                scrollTop: editor.getScrollTop()
            };
        }, CAPTURE);

        editorDom.addEventListener('touchmove', function (event) {
            if (pinchStart && event.touches.length === 2) {
                var scale = touchDistance(event) / (pinchStart.distance || 1);
                window.spark.setFontSize(pinchStart.fontSize * scale);
                return;
            }
            if (!touchStart || event.touches.length !== 1) {
                return;
            }
            var touch = event.touches[0];
            if (Math.abs(touch.clientX - touchStart.x) > 8 || Math.abs(touch.clientY - touchStart.y) > 8) {
                if (!touchStart.moved) {
                    // 一旦判定为滑动，就把编辑器的焦点摘掉并通知宿主收起输入法：
                    // 编辑器获得过焦点后 WebView 会把输入法一直挂着，之后随便一滑键盘就回来。
                    touchStart.moved = true;
                    blurEditor();
                }
            }
        }, CAPTURE);

        // 横向平移常被浏览器 / Monaco 抢走手势，此时只会收到 touchcancel；一样算「滑动过」
        editorDom.addEventListener('touchcancel', function () {
            if (touchStart) {
                touchStart.moved = true;
            }
            pinchStart = null;
            blurEditor();
        }, CAPTURE);

        editorDom.addEventListener('touchend', function (event) {
            if (event.touches.length < 2) {
                pinchStart = null;
            }
            if (event.touches.length > 0) {
                return;
            }
            var start = touchStart;
            touchStart = null;
            if (!start) {
                return;
            }
            if (start.moved || Date.now() - start.at > 600) {
                // 滑动与长按都不该把键盘带出来；编辑器已获得过焦点时尤其要主动收一次
                blurEditor();
                return;
            }
            editor.focus();
            post('onFocus');
        }, CAPTURE);

        post('onReady');
    });
})();
