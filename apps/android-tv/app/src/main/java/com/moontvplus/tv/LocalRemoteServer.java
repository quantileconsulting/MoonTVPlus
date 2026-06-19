package com.moontvplus.tv;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Locale;

public class LocalRemoteServer {
    private static final String TAG = "MoonTVLocalRemote";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int[] PORTS = new int[]{9978, 9979, 9980};

    private final RemoteCommandHandler handler;
    private final String token;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private int port = -1;

    public LocalRemoteServer(RemoteCommandHandler handler) {
        this.handler = handler;
        this.token = createToken();
    }

    public synchronized void start() {
        if (running) return;
        for (int candidate : PORTS) {
            try {
                serverSocket = new ServerSocket(candidate);
                port = candidate;
                running = true;
                serverThread = new Thread(this::acceptLoop, "MoonTV-LAN-Remote");
                serverThread.start();
                Log.i(TAG, "Local remote server started on " + candidate);
                return;
            } catch (IOException error) {
                Log.w(TAG, "Port unavailable: " + candidate, error);
            }
        }
        Log.e(TAG, "Unable to start local remote server");
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
    }

    public int getPort() { return port; }
    public String getToken() { return token; }

    public String getRemoteUrl() {
        String ip = getLocalIpAddress();
        if (ip == null || port <= 0) return null;
        return "http://" + ip + ":" + port + "/remote?token=" + token;
    }

    private void acceptLoop() {
        while (running && serverSocket != null) {
            try {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket), "MoonTV-LAN-Client").start();
            } catch (IOException error) {
                if (running) Log.w(TAG, "Accept failed", error);
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) return;

            String path = "/";
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) path = parts[1];

            String line;
            String websocketKey = null;
            boolean websocket = false;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("sec-websocket-key:")) websocketKey = line.substring(line.indexOf(':') + 1).trim();
                if (lower.startsWith("upgrade:") && lower.contains("websocket")) websocket = true;
            }

            if (websocket) {
                if (!isAuthorized(path) || websocketKey == null) {
                    writeText(s.getOutputStream(), 403, "text/plain; charset=utf-8", "Forbidden");
                    return;
                }
                handleWebSocket(s, websocketKey);
                return;
            }

            if (path.startsWith("/health")) {
                writeText(s.getOutputStream(), 200, "application/json; charset=utf-8", "{\"ok\":true}");
            } else if (path.startsWith("/remote") || path.equals("/") || path.startsWith("/?")) {
                writeText(s.getOutputStream(), 200, "text/html; charset=utf-8", remoteHtml());
            } else {
                writeText(s.getOutputStream(), 404, "text/plain; charset=utf-8", "Not found");
            }
        } catch (Exception error) {
            Log.w(TAG, "Client handling failed", error);
        }
    }

    private boolean isAuthorized(String path) {
        return path != null && path.contains("token=" + token);
    }

    private void handleWebSocket(Socket socket, String websocketKey) throws Exception {
        OutputStream out = socket.getOutputStream();
        String accept = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((websocketKey + WS_GUID).getBytes(StandardCharsets.UTF_8)),
                Base64.NO_WRAP
        );
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();

        InputStream in = socket.getInputStream();
        while (running && !socket.isClosed()) {
            String message = readWebSocketText(in);
            if (message == null) break;
            dispatchMessage(message);
        }
    }

    private String readWebSocketText(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 < 0) return null;
        int b2 = in.read();
        if (b2 < 0) return null;
        int opcode = b1 & 0x0F;
        if (opcode == 8) return null;
        boolean masked = (b2 & 0x80) != 0;
        long length = b2 & 0x7F;
        if (length == 126) length = ((long) in.read() << 8) | in.read();
        else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | in.read();
        }
        if (length < 0 || length > 64 * 1024) return null;
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask);
        byte[] data = new byte[(int) length];
        readFully(in, data);
        if (masked) {
            for (int i = 0; i < data.length; i++) data[i] = (byte) (data[i] ^ mask[i % 4]);
        }
        if (opcode != 1) return "";
        return new String(data, StandardCharsets.UTF_8);
    }

    private void readFully(InputStream in, byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int read = in.read(data, offset, data.length - offset);
            if (read < 0) throw new IOException("Unexpected EOF");
            offset += read;
        }
    }

    private void dispatchMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type");
            JSONObject command = json.optJSONObject("command");
            if (command == null) command = json;
            if ("key".equals(type)) {
                handler.onRemoteKey(command.optString("key"), command.optBoolean("repeat", false), command.optString("digit", null));
            } else if ("text".equals(type)) {
                handler.onRemoteText(command.optString("mode"), command.optString("text", ""));
            }
        } catch (Exception error) {
            Log.w(TAG, "Invalid remote message: " + message, error);
        }
    }

    private void writeText(OutputStream out, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "OK" : status == 403 ? "Forbidden" : "Not Found";
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static String getLocalIpAddress() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        String ip = address.getHostAddress();
                        if (!ip.startsWith("127.")) return ip;
                    }
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Unable to resolve local IP", error);
        }
        return null;
    }

    private static String createToken() {
        byte[] data = new byte[5];
        new SecureRandom().nextBytes(data);
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE).replace("=", "");
    }

    private String remoteHtml() {
        return "<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>" +
                "<title>MoonTVPlus 局域网遥控器</title><style>" +
                ":root{color-scheme:dark;--bg:#070816;--panel:rgba(255,255,255,.09);--panel2:rgba(255,255,255,.14);--line:rgba(255,255,255,.16);--text:#f8fafc;--muted:#a5b4fc;--primary:#6366f1;--ok:#10b981;--danger:#fb7185}*{box-sizing:border-box}body{margin:0;min-height:100svh;font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:radial-gradient(circle at 20% 0%,#312e81 0,#111827 34%,#070816 100%);color:var(--text);display:flex;align-items:center;justify-content:center;padding:18px}.shell{width:min(430px,100%)}.hero{margin-bottom:16px}.pill{display:inline-flex;gap:8px;align-items:center;border:1px solid var(--line);background:rgba(99,102,241,.16);color:#c7d2fe;border-radius:999px;padding:7px 11px;font-size:12px;font-weight:800}.dot{width:8px;height:8px;border-radius:999px;background:var(--ok);box-shadow:0 0 18px var(--ok)}h1{font-size:clamp(32px,10vw,54px);line-height:.9;margin:14px 0 8px;letter-spacing:-.07em;font-weight:950}.sub{margin:0;color:#cbd5e1;font-size:14px;line-height:1.6}.card{border:1px solid var(--line);background:linear-gradient(180deg,rgba(255,255,255,.13),rgba(255,255,255,.06));box-shadow:0 24px 90px rgba(0,0,0,.42);backdrop-filter:blur(18px);border-radius:30px;padding:18px}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.btn{min-height:66px;min-width:66px;border:1px solid var(--line);border-radius:22px;background:var(--panel);color:var(--text);font-size:22px;font-weight:900;cursor:pointer;touch-action:none;transition:background .18s,border-color .18s,transform .12s,box-shadow .18s}.btn:active{transform:translateY(1px);background:var(--panel2)}.btn:focus-visible{outline:3px solid #818cf8;outline-offset:2px}.ok{background:linear-gradient(135deg,#10b981,#34d399);color:#04130d;box-shadow:0 12px 32px rgba(16,185,129,.26)}.wide{grid-column:span 3}.row{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:12px}.small{min-height:54px;font-size:15px;border-radius:18px}.input{display:flex;gap:10px;margin-top:14px}.input input{min-height:50px;min-width:0;flex:1;border:1px solid var(--line);border-radius:18px;background:rgba(255,255,255,.08);color:var(--text);padding:0 14px;font-size:16px;outline:none}.input input:focus{border-color:#818cf8}.send{min-height:50px;border-radius:18px;border:0;background:#6366f1;color:white;font-weight:900;padding:0 16px;cursor:pointer}.status{margin-top:12px;color:#a5b4fc;font-size:12px;text-align:center}@media (prefers-reduced-motion:reduce){*{transition:none!important}}" +
                "</style></head><body><main class='shell'><section class='hero'><div class='pill'><span class='dot'></span><span>LAN REMOTE</span></div><h1>电视遥控器</h1><p class='sub'>同一局域网直连 MoonTVPlus TV，低延迟控制方向、播放和文本输入。</p></section><section class='card' aria-label='遥控器按键'><div class='grid'><span></span><button class='btn' data-key='up' aria-label='上'>↑</button><span></span><button class='btn' data-key='left' aria-label='左'>←</button><button class='btn ok' data-key='ok' aria-label='确定'>OK</button><button class='btn' data-key='right' aria-label='右'>→</button><span></span><button class='btn' data-key='down' aria-label='下'>↓</button><span></span></div><div class='row'><button class='btn small' data-key='back'>返回</button><button class='btn small' data-key='home'>首页</button><button class='btn small' data-key='menu'>菜单</button></div><div class='row'><button class='btn small' data-key='pageUp'>上一页</button><button class='btn small' data-key='playPause'>播放/暂停</button><button class='btn small' data-key='pageDown'>下一页</button></div><div class='input'><input id='text' placeholder='输入文字发送到电视' autocomplete='off'><button class='send' id='send'>发送</button></div><div class='status' id='status'>正在连接电视...</div></section></main><script>" +
                "const token=new URLSearchParams(location.search).get('token')||'';const statusEl=document.getElementById('status');let ws;function setStatus(t){statusEl.textContent=t}function connect(){ws=new WebSocket('ws://'+location.host+'/ws?token='+encodeURIComponent(token));ws.onopen=()=>setStatus('已连接');ws.onclose=()=>{setStatus('连接断开，正在重连...');setTimeout(connect,1200)};ws.onerror=()=>setStatus('连接异常，请确认电视与手机在同一局域网')}function send(o){if(ws&&ws.readyState===1)ws.send(JSON.stringify(o))}connect();function key(k,repeat=false){send({version:1,type:'key',command:{key:k,repeat}})}document.querySelectorAll('[data-key]').forEach(btn=>{let timer=null,loop=null;const k=btn.dataset.key;const stop=()=>{clearTimeout(timer);clearInterval(loop);timer=loop=null};btn.addEventListener('pointerdown',e=>{e.preventDefault();key(k,false);timer=setTimeout(()=>{loop=setInterval(()=>key(k,true),135)},380)});['pointerup','pointercancel','pointerleave'].forEach(n=>btn.addEventListener(n,stop));});document.getElementById('send').onclick=()=>{const input=document.getElementById('text');send({version:1,type:'text',command:{mode:'replace',text:input.value}});setStatus('文本已发送')};document.getElementById('text').addEventListener('keydown',e=>{if(e.key==='Enter')document.getElementById('send').click()});" +
                "</script></body></html>";
    }
}
