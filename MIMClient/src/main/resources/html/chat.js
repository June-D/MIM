var chat = {};

/**
 * 上一条消息的日期（用于插入日期分隔线）。
 */
chat._lastDate = null;

/**
 * 追加一条已渲染好的消息 HTML。
 *
 * 服务端/Java 侧传入的 html 已是完整片段（含 data-id 与 right/recalled 类），
 * 这里只负责插入、必要时补日期分隔线、滚动到底部。
 */
chat.append = function (html) {
    var content = document.getElementById("content");

    // 检测日期，跨天时插一条分隔线
    var dateMatch = html.match(/(\d{4}\/\d{2}\/\d{2})/);
    if (dateMatch && dateMatch[1] !== chat._lastDate) {
        chat._lastDate = dateMatch[1];
        var divider = document.createElement("div");
        divider.className = "day-divider";
        divider.textContent = dateMatch[1];
        content.appendChild(divider);
    }

    var wrap = document.createElement("div");
    wrap.innerHTML = html;

    // 取出最外层消息节点（HtmlUtil 输出单个 div）
    var node = wrap.firstElementChild;
    if (!node) {
        return;
    }
    content.appendChild(node);
    var recall = node.querySelector(".recall-btn");
    if (recall) {
        recall.addEventListener("click", function (event) {
            event.preventDefault();
            chat.requestRecall(recall);
        });
    }
    chat.scrollToBottom();
};

chat.clear = function () {
    document.getElementById("content").innerHTML = "";
    chat._lastDate = null;
};

chat.scrollToBottom = function () {
    window.scrollTo(0, document.body.scrollHeight);
};

/**
 * 对方已读到 beforeId（含）为止。只改自己发出的、还在窗口里的消息。
 */
chat.markReadThrough = function (beforeId) {
    var limit = Number(beforeId);
    if (!limit) {
        return;
    }
    var nodes = document.querySelectorAll(".message-item.right");
    for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (node.classList.contains("recalled")) {
            continue;
        }
        var serverId = Number(node.getAttribute("data-server-id"));
        if (!serverId || serverId > limit) {
            continue;
        }
        var state = node.querySelector(".read-state");
        if (state) {
            state.textContent = "已读";
            state.className = "read-state read";
        }
    }
};

/**
 * 把指定消息标记为已撤回：清空正文，替换为占位文本。
 *
 * 服务端撤回后不会再下发原文，因此这里必须就地抹掉本地已渲染的内容。
 */
chat.markRecalled = function (clientMsgId) {
    if (!clientMsgId) {
        return;
    }
    var nodes = document.querySelectorAll("[data-id='" + clientMsgId + "']");
    for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        node.classList.add("recalled");
        var bubble = node.querySelector(".bubble");
        if (bubble) {
            var mine = node.classList.contains("right");
            bubble.textContent = mine ? "你撤回了一条消息" : "对方撤回了一条消息";
        }
    }
};

/**
 * 用页面坐标找出「撤回」按钮对应的消息。JavaFX 的鼠标事件经常到不了网页按钮上，
 * 所以由 Java 把点击坐标传进来。
 */
chat.recallTargetAt = function (x, y) {
    var ratio = window.devicePixelRatio || 1;
    var points = [[x, y]];
    if (ratio !== 1) {
        points.push([x / ratio, y / ratio]);
        points.push([x * ratio, y * ratio]);
    }
    for (var i = 0; i < points.length; i++) {
        var found = chat._recallAtPoint(points[i][0], points[i][1]);
        if (found) {
            return found;
        }
    }
    return "";
};

chat._recallAtPoint = function (x, y) {
    var el = document.elementFromPoint(x, y);
    while (el && el !== document.documentElement) {
        if (el.classList && el.classList.contains("recall-btn")) {
            var node = el;
            while (node && node !== document.body) {
                if (node.getAttribute && node.getAttribute("data-id")) {
                    return node.getAttribute("data-id");
                }
                node = node.parentNode;
            }
            return "";
        }
        el = el.parentNode;
    }
    return "";
};

/**
 * 点「撤回」时，点中的往往是按钮里的文字，this 才是按钮本身。
 * 从按钮往上找到带 data-id 的消息，再交给 Java。
 */
chat.requestRecall = function (button) {
    var node = button ? button.parentNode : null;
    while (node && node !== document.body) {
        if (node.dataset && node.dataset.id) {
            if (window.javaBridge && window.javaBridge.onRecallRequest) {
                window.javaBridge.onRecallRequest(node.dataset.id);
            }
            return;
        }
        node = node.parentNode;
    }
};

document.addEventListener("contextmenu", function (event) {
    var node = event.target;
    while (node && node !== document.body) {
        if (node.dataset && node.dataset.id) {
            event.preventDefault();
            if (window.javaBridge && window.javaBridge.onRecallRequest) {
                window.javaBridge.onRecallRequest(node.dataset.id);
            } else {
                // 撤回不可用时给出明确反馈，而不是静默无反应
                console.warn("撤回功能未就绪");
            }
            return;
        }
        node = node.parentNode;
    }
});
