# -*- coding: utf-8 -*-
"""
九号 Hook 上报服务：在电脑运行，手机模块把检测/错误信息发到这里，浏览器打开查看。
用法：python server.py  然后浏览器打开 http://本机IP:8765
"""
import socket
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
from datetime import datetime

PORT = 8765


class SafeHTTPServer(HTTPServer):
    """避免 Windows 下主机名含中文时 socket.getfqdn() 的 UnicodeDecodeError"""
    def server_bind(self):
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.socket.bind(self.server_address)
        self.server_name = self.server_address[0] or "localhost"
        self.server_port = self.server_address[1]
MAX_ITEMS = 200
reports = []

class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass  # 少打控制台日志

    def do_GET(self):
        if self.path == "/clear":
            reports.clear()
            self.send_response(302)
            self.send_header("Location", "/")
            self.end_headers()
            return

        if self.path.startswith("/report?"):
            query = urllib.parse.urlparse(self.path).query
            params = urllib.parse.parse_qs(query)
            msg = params.get("msg", [""])[0]
            msg = urllib.parse.unquote(msg)
            tag = params.get("tag", ["info"])[0]
            reports.append({
                "time": datetime.now().strftime("%H:%M:%S"),
                "tag": tag,
                "msg": msg
            })
            if len(reports) > MAX_ITEMS:
                reports.pop(0)
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(b"ok")
            return

        if self.path == "/" or self.path == "":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            try:
                html = self.make_html()
                self.wfile.write(html.encode("utf-8"))
            except Exception as e:
                self.wfile.write(
                    ("<html><body><h1>make_html 异常</h1><pre>%s</pre></body></html>" % str(e)
                    ).encode("utf-8")
                )
            return

        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path == "/report":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8", errors="replace")
            tag = "report"
            if "=" in body:
                for part in body.split("&"):
                    if part.startswith("msg="):
                        body = urllib.parse.unquote(part[4:])
                    elif part.startswith("tag="):
                        tag = urllib.parse.unquote(part[4:])
            reports.append({
                "time": datetime.now().strftime("%H:%M:%S"),
                "tag": tag,
                "msg": body
            })
            if len(reports) > MAX_ITEMS:
                reports.pop(0)
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(b"ok")
            return
        self.send_response(404)
        self.end_headers()

    def make_html(self):
        def escape(s):
            if not s:
                return ""
            s = s.replace("<", "&lt;").replace(">", "&gt;").replace("{", "{{").replace("}", "}}")
            return s
        rows = "".join(
            '<tr><td>{}</td><td class="tag">{}</td><td class="msg">{}</td></tr>'.format(
                r["time"], r["tag"], escape(r["msg"])
            )
            for r in reversed(reports)
        )
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>九号 Hook 上报</title>
<style>
body { font-family: sans-serif; margin: 12px; background: #1e1e1e; color: #ddd; }
h1 { font-size: 16px; }
table { width: 100%%; border-collapse: collapse; }
th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #333; }
th { color: #888; }
.tag { color: #7ec; width: 80px; }
.msg { word-break: break-all; }
.warn { color: #fa0; }
.err { color: #f66; }
</style>
<meta http-equiv="refresh" content="3">
</head><body>
<h1>九号 Hook 上报 (每 3 秒刷新) | 共 %d 条 <a href="/clear" style="color:#7ec;margin-left:12px;">清除历史</a>
 <a href="#" onclick="copyRecent(10);return false;" style="color:#7ec;margin-left:8px;">复制最近10条</a>
 <a href="#" onclick="copyRecent(20);return false;" style="color:#7ec;margin-left:6px;">复制最近20条</a>
 <a href="#" onclick="copyRecent(100);return false;" style="color:#7ec;margin-left:6px;">复制最近100条</a>
</h1>
<table id="logTable"><tr><th>时间</th><th>类型</th><th>内容</th></tr>
%s
</table>
<script>
function copyRecent(n) {
  var table = document.getElementById("logTable");
  if (!table || !table.rows || table.rows.length <= 1) { alert("暂无日志"); return; }
  var rows = [];
  for (var i = 1; i < table.rows.length && rows.length < n; i++) {
    var r = table.rows[i];
    var cells = r.cells;
    if (cells.length >= 3)
      rows.push((cells[0].innerText || "").replace(/\\t/g," ") + "\\t" + (cells[1].innerText || "").replace(/\\t/g," ") + "\\t" + (cells[2].innerText || "").replace(/\\n/g," "));
  }
  var text = rows.join("\\n");
  if (!text) { alert("暂无日志"); return; }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(function() { alert("已复制最近 " + rows.length + " 条"); }).catch(function() { fallbackCopy(text, rows.length); });
  } else { fallbackCopy(text, rows.length); }
}
function fallbackCopy(text, count) {
  var ta = document.createElement("textarea"); ta.value = text; ta.style.position="fixed"; ta.style.left="-9999px"; document.body.appendChild(ta); ta.select();
  try { document.execCommand("copy"); alert("已复制最近 " + count + " 条"); } catch (e) { alert("复制失败: " + e); }
  document.body.removeChild(ta);
}
</script>
</body></html>""" % (len(reports), rows if rows else "<tr><td colspan=3>暂无上报</td></tr>")


if __name__ == "__main__":
    host = "0.0.0.0"
    server = SafeHTTPServer((host, PORT), Handler)
    print("九号 Hook 上报服务: http://%s:%d" % (host, PORT))
    print("本机访问: http://127.0.0.1:%d" % PORT)
    print("手机/同局域网: 用 ipconfig 查本机 IP，浏览器打开 http://<本机IP>:%d" % PORT)
    print("手机模块已写死上报地址，直接打开九号即可。按 Ctrl+C 停止服务。")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
