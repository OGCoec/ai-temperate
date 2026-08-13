"""让 mitmproxy 立即转发 SSE 响应体，避免 Android 等到响应结束才收到事件。"""

from mitmproxy import http


def responseheaders(flow: http.HTTPFlow) -> None:
    """在响应体被缓存前，仅为 SSE 响应启用流式转发。"""
    content_type = flow.response.headers.get("content-type", "").lower()
    if content_type.startswith("text/event-stream"):
        flow.response.stream = True
