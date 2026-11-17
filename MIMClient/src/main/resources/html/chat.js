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
 * 右键消息：把 clientMsgId 回传给 Java 层发起撤回。
 */
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
