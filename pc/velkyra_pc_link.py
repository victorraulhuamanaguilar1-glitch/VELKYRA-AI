# -*- coding: utf-8 -*-
"""
VELKYRA PC Link — deja que ODÍN (el asistente de voz del celular) controle esta laptop por voz.
Funciona solo dentro de su red wifi. Para conectarse, ODÍN necesita el código de 6 números que se muestra aquí.
Desarrollado por VELKYRA.
"""
import ctypes
import glob
import json
import os
import random
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PUERTO_UDP = 47800
PUERTO_HTTP = 47801
CARPETA = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "VELKYRA")
CONFIG = os.path.join(CARPETA, "pc_link.json")
NOMBRE_PC = socket.gethostname()

# ---------------------------------------------------------------- configuración

def cargar():
    try:
        with open(CONFIG, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}

def guardar(c):
    os.makedirs(CARPETA, exist_ok=True)
    with open(CONFIG, "w", encoding="utf-8") as f:
        json.dump(c, f)

conf = cargar()
if "pin" not in conf:
    conf["pin"] = "%06d" % random.randint(0, 999999)
    conf["tokens"] = []
    guardar(conf)

registro = []
def log(t):
    registro.append(time.strftime("%H:%M  ") + t)
    del registro[:-40]

# ---------------------------------------------------------------- acciones de Windows

user32 = ctypes.windll.user32 if os.name == "nt" else None
VK = {"vol_arriba": 0xAF, "vol_abajo": 0xAE, "silencio": 0xAD, "play": 0xB3, "siguiente": 0xB0, "anterior": 0xB1, "detener": 0xB2}

def tecla(vk, veces=1):
    for _ in range(veces):
        user32.keybd_event(vk, 0, 0, 0)
        user32.keybd_event(vk, 0, 2, 0)
        time.sleep(0.01)

def cmd(lista):
    subprocess.Popen(lista, shell=False, creationflags=0x08000000)  # sin ventana

APPS = {
    "word": "winword", "excel": "excel", "powerpoint": "powerpnt", "power point": "powerpnt", "outlook": "outlook",
    "chrome": "chrome", "google chrome": "chrome", "navegador": "msedge", "edge": "msedge", "firefox": "firefox",
    "bloc de notas": "notepad", "notas": "notepad", "calculadora": "calc", "paint": "mspaint",
    "explorador": "explorer", "archivos": "explorer", "mis archivos": "explorer", "mis documentos": "explorer",
    "configuracion": "ms-settings:", "ajustes": "ms-settings:", "spotify": "spotify:", "vlc": "vlc",
    "cmd": "cmd", "terminal": "wt", "simbolo del sistema": "cmd", "administrador de tareas": "taskmgr",
    "whatsapp": "whatsapp:", "camara": "microsoft.windows.camera:", "correo": "outlookmail:",
    "youtube": "https://www.youtube.com", "facebook": "https://www.facebook.com", "gmail": "https://mail.google.com",
}
PROCESOS = {
    "word": "WINWORD.EXE", "excel": "EXCEL.EXE", "powerpoint": "POWERPNT.EXE", "chrome": "chrome.exe", "google chrome": "chrome.exe",
    "edge": "msedge.exe", "navegador": "msedge.exe", "firefox": "firefox.exe", "bloc de notas": "notepad.exe", "notas": "notepad.exe",
    "calculadora": "CalculatorApp.exe", "paint": "mspaint.exe", "spotify": "Spotify.exe", "vlc": "vlc.exe", "whatsapp": "WhatsApp.exe",
    "outlook": "OUTLOOK.EXE", "explorador": "explorer.exe",
}

def normal(s):
    s = s.lower().strip()
    for a, b in zip("áéíóúüñ", "aeiouun"):
        s = s.replace(a, b)
    return re.sub(r"^(el|la|los|las|mi|mis)\s+", "", s)

def accesos():
    rutas = [os.path.join(os.environ.get("ProgramData", ""), r"Microsoft\Windows\Start Menu\Programs"),
             os.path.join(os.environ.get("APPDATA", ""), r"Microsoft\Windows\Start Menu\Programs"),
             os.path.join(os.path.expanduser("~"), "Desktop"), os.path.join(os.environ.get("PUBLIC", ""), "Desktop")]
    l = []
    for r in rutas:
        l += glob.glob(os.path.join(r, "**", "*.lnk"), recursive=True)
    return l

def abrir(nombre):
    n = normal(nombre)
    if n in APPS:
        destino = APPS[n]
        if destino.startswith("http"):
            webbrowser.open(destino)
        else:
            os.startfile(destino) if ":" in destino else cmd(["cmd", "/c", "start", "", destino])
        return "Abrí %s en la laptop." % nombre
    mejor = None
    for lnk in accesos():
        base = normal(os.path.splitext(os.path.basename(lnk))[0])
        if base == n:
            mejor = lnk
            break
        if n in base and (mejor is None or len(base) < len(normal(os.path.basename(mejor)))):
            mejor = lnk
    if mejor:
        os.startfile(mejor)
        return "Abrí %s en la laptop." % os.path.splitext(os.path.basename(mejor))[0]
    return "No encontré «%s» en la laptop." % nombre

def cerrar(nombre):
    n = normal(nombre)
    proc = PROCESOS.get(n) or (re.sub(r"\s+", "", n) + ".exe")
    r = subprocess.run(["taskkill", "/im", proc], capture_output=True, creationflags=0x08000000)
    if r.returncode != 0:
        r = subprocess.run(["taskkill", "/im", proc, "/f"], capture_output=True, creationflags=0x08000000)
    return ("Cerré %s." % nombre) if r.returncode == 0 else ("%s no estaba abierto." % nombre.capitalize())

def youtube(q):
    try:
        req = urllib.request.Request("https://www.youtube.com/results?search_query=" + urllib.parse.quote(q),
                                     headers={"User-Agent": "Mozilla/5.0", "Accept-Language": "es-PE,es;q=0.9"})
        html = urllib.request.urlopen(req, timeout=10).read().decode("utf-8", "ignore")
        m = re.search(r'"videoId":"([\w-]{11})"', html)
        if m:
            webbrowser.open("https://www.youtube.com/watch?v=%s&autoplay=1" % m.group(1))
            return "Reproduciendo %s en la laptop." % q
    except Exception:
        pass
    webbrowser.open("https://www.youtube.com/results?search_query=" + urllib.parse.quote(q))
    return "Busqué %s en YouTube en la laptop." % q

class Bateria(ctypes.Structure):
    _fields_ = [("ACLineStatus", ctypes.c_byte), ("BatteryFlag", ctypes.c_byte), ("BatteryLifePercent", ctypes.c_byte),
                ("SystemStatusFlag", ctypes.c_byte), ("BatteryLifeTime", ctypes.c_ulong), ("BatteryFullLifeTime", ctypes.c_ulong)]

def bateria():
    b = Bateria()
    ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(b))
    pct = b.BatteryLifePercent & 0xFF
    if pct == 255:
        return "La laptop está conectada a la corriente."
    return "La laptop tiene %d%% de batería%s." % (pct, ", y está cargando" if b.ACLineStatus == 1 else "")

def ejecutar(accion, valor):
    v = (valor or "").strip()
    if accion == "estado":
        return "La laptop %s está encendida y lista." % NOMBRE_PC
    if accion == "apagar":
        cmd(["shutdown", "/s", "/t", "30"]); return "La laptop se apagará en 30 segundos. Diga «cancela el apagado» si cambia de opinión."
    if accion == "reiniciar":
        cmd(["shutdown", "/r", "/t", "30"]); return "La laptop se reiniciará en 30 segundos."
    if accion == "cancelar":
        cmd(["shutdown", "/a"]); return "Cancelé el apagado de la laptop."
    if accion == "suspender":
        threading.Timer(2, lambda: cmd(["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"])).start(); return "Suspendiendo la laptop."
    if accion == "bloquear":
        user32.LockWorkStation(); return "Bloqueé la laptop."
    if accion == "volumen":
        if v == "subir": tecla(VK["vol_arriba"], 5); return "Subí el volumen de la laptop."
        if v == "bajar": tecla(VK["vol_abajo"], 5); return "Bajé el volumen de la laptop."
        if v == "silenciar": tecla(VK["silencio"]); return "Silencié la laptop."
        if v.isdigit():
            tecla(VK["vol_abajo"], 50); tecla(VK["vol_arriba"], max(0, min(100, int(v))) // 2)
            return "Volumen de la laptop al %s por ciento." % v
    if accion == "media":
        tecla(VK.get(v, VK["play"])); return {"play": "Listo.", "siguiente": "Siguiente.", "anterior": "Anterior.", "detener": "Detenido."}.get(v, "Listo.")
    if accion == "abrir":
        return abrir(v)
    if accion == "cerrar":
        return cerrar(v)
    if accion == "youtube":
        return youtube(v)
    if accion == "buscar":
        webbrowser.open("https://www.google.com/search?q=" + urllib.parse.quote(v)); return "Busqué %s en la laptop." % v
    if accion == "web":
        webbrowser.open(v if v.startswith("http") else "https://" + v); return "Abrí la página en la laptop."
    if accion == "bateria":
        return bateria()
    if accion == "escribir":
        tk_root.clipboard_clear(); tk_root.clipboard_append(v); tk_root.update()
        user32.keybd_event(0x11, 0, 0, 0); user32.keybd_event(0x56, 0, 0, 0); user32.keybd_event(0x56, 0, 2, 0); user32.keybd_event(0x11, 0, 2, 0)
        return "Escribí el texto en la laptop."
    return "No sé hacer eso en la laptop todavía."

# ---------------------------------------------------------------- red

class Manejador(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def responder(self, codigo, datos):
        b = json.dumps(datos).encode("utf-8")
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.startswith("/estado"):
            self.responder(200, {"app": "VELKYRA_PC", "nombre": NOMBRE_PC})
        else:
            self.responder(404, {})

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", "0"))
            d = json.loads(self.rfile.read(n).decode("utf-8") or "{}")
        except Exception:
            return self.responder(400, {"ok": False})
        if self.path.startswith("/emparejar"):
            if str(d.get("pin", "")).strip() == conf["pin"]:
                t = secrets.token_hex(16)
                conf.setdefault("tokens", []).append(t)
                guardar(conf)
                log("Celular conectado: %s" % d.get("telefono", "ODÍN"))
                return self.responder(200, {"ok": True, "token": t, "nombre": NOMBRE_PC})
            log("Intento de conexión con código incorrecto")
            return self.responder(403, {"ok": False, "msg": "Código incorrecto"})
        if self.path.startswith("/orden"):
            if d.get("token") not in conf.get("tokens", []):
                return self.responder(403, {"ok": False, "msg": "No autorizado. Vuelva a conectar el celular."})
            accion, valor = d.get("accion", ""), d.get("valor", "")
            try:
                msg = ejecutar(accion, valor)
            except Exception as e:
                msg = "No pude hacerlo en la laptop: %s" % e
            log("%s %s → %s" % (accion, valor, msg))
            return self.responder(200, {"ok": True, "msg": msg})
        self.responder(404, {})

def servidor_http():
    ThreadingHTTPServer(("0.0.0.0", PUERTO_HTTP), Manejador).serve_forever()

def servidor_udp():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", PUERTO_UDP))
    while True:
        try:
            datos, origen = s.recvfrom(256)
            if datos.startswith(b"VELKYRA_PC?"):
                s.sendto(("VELKYRA_PC|%s|%d" % (NOMBRE_PC, PUERTO_HTTP)).encode("utf-8"), origen)
        except Exception:
            time.sleep(1)

def ip_local():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "sin red"

# ---------------------------------------------------------------- inicio con Windows

def inicio_windows(activar):
    import winreg
    k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_SET_VALUE | winreg.KEY_QUERY_VALUE)
    if activar:
        winreg.SetValueEx(k, "VELKYRA PC Link", 0, winreg.REG_SZ, '"%s" --oculto' % sys.executable)
    else:
        try:
            winreg.DeleteValue(k, "VELKYRA PC Link")
        except FileNotFoundError:
            pass
    winreg.CloseKey(k)

def inicio_activado():
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run")
        winreg.QueryValueEx(k, "VELKYRA PC Link")
        return True
    except Exception:
        return False

# ---------------------------------------------------------------- ventana

AZUL, CIAN, TEXTO, SUAVE = "#050B18", "#22D3FF", "#E8F7FF", "#7FA6C2"
tk_root = tk.Tk()

def ventana():
    r = tk_root
    r.title("VELKYRA PC Link")
    r.configure(bg=AZUL)
    r.geometry("420x520")
    r.resizable(False, False)
    tk.Label(r, text="VELKYRA PC Link", font=("Segoe UI", 20, "bold"), fg=CIAN, bg=AZUL).pack(pady=(22, 2))
    tk.Label(r, text="Controle esta laptop con ODÍN desde su celular", font=("Segoe UI", 10), fg=SUAVE, bg=AZUL).pack()
    tk.Label(r, text="Código para conectar", font=("Segoe UI", 11), fg=TEXTO, bg=AZUL).pack(pady=(26, 0))
    pin = tk.Label(r, text=" ".join(conf["pin"]), font=("Consolas", 34, "bold"), fg=CIAN, bg=AZUL)
    pin.pack()
    tk.Label(r, text="En el celular diga: «Odín, conecta con mi laptop»\ny luego dígale este código.", font=("Segoe UI", 10), fg=SUAVE, bg=AZUL, justify="center").pack(pady=(4, 10))
    info = tk.Label(r, text="", font=("Segoe UI", 9), fg=SUAVE, bg=AZUL)
    info.pack()

    def nuevo():
        conf["pin"] = "%06d" % random.randint(0, 999999)
        conf["tokens"] = []
        guardar(conf)
        pin.config(text=" ".join(conf["pin"]))
        log("Nuevo código. Los celulares conectados deberán volver a conectarse.")
    tk.Button(r, text="Nuevo código (desconecta celulares)", command=nuevo, bg="#0C1A33", fg=TEXTO, relief="flat", padx=10, pady=4).pack(pady=6)

    v = tk.BooleanVar(value=inicio_activado())
    def cambiar():
        try:
            inicio_windows(v.get())
        except Exception as e:
            log("No pude cambiar el inicio: %s" % e)
    tk.Checkbutton(r, text="Iniciar con Windows", variable=v, command=cambiar, bg=AZUL, fg=TEXTO, selectcolor="#0C1A33",
                   activebackground=AZUL, activeforeground=TEXTO).pack()
    caja = tk.Text(r, height=8, bg="#08142A", fg=SUAVE, relief="flat", font=("Consolas", 9))
    caja.pack(fill="both", expand=True, padx=16, pady=(10, 4))
    tk.Label(r, text="Desarrollado por VELKYRA", font=("Segoe UI", 8), fg="#3E5F7A", bg=AZUL).pack(pady=(0, 8))

    def refrescar():
        info.config(text="Laptop: %s   ·   IP: %s   ·   %d celular(es) conectado(s)" % (NOMBRE_PC, ip_local(), len(conf.get("tokens", []))))
        caja.delete("1.0", "end")
        caja.insert("end", "\n".join(registro[-12:]) or "Esperando órdenes de ODÍN…")
        r.after(2000, refrescar)
    refrescar()
    # Cerrar la ventana solo la minimiza: el servicio sigue funcionando
    r.protocol("WM_DELETE_WINDOW", r.iconify)
    if "--oculto" in sys.argv:
        r.iconify()
    r.mainloop()

if __name__ == "__main__":
    try:
        socket.create_connection(("127.0.0.1", PUERTO_HTTP), timeout=0.5).close()
        ctypes.windll.user32.MessageBoxW(0, "VELKYRA PC Link ya está funcionando.", "VELKYRA PC Link", 0x40)
        sys.exit(0)
    except OSError:
        pass
    threading.Thread(target=servidor_http, daemon=True).start()
    threading.Thread(target=servidor_udp, daemon=True).start()
    log("Listo. Esperando a ODÍN en la red wifi.")
    ventana()
