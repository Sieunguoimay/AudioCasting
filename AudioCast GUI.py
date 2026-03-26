#!/usr/bin/env python3
"""
AudioCast GUI - Modern desktop GUI for AudioCast server/receiver
"""

import tkinter as tk
from tkinter import messagebox
import subprocess
import threading
import os
import sys
import platform
import json
import time

PYSTRAY_AVAILABLE = False
TrayIcon = None
TrayMenu = None
TrayMenuItem = None
Image = None
ImageDraw = None

try:
    from pystray import Icon as TrayIcon, Menu as TrayMenu, MenuItem as TrayMenuItem
    from PIL import Image as PILImage, ImageDraw as PILImageDraw
    Image = PILImage
    ImageDraw = PILImageDraw
    PYSTRAY_AVAILABLE = True
except ImportError:
    pass

try:
    import urllib.request
    HTTP_AVAILABLE = True
except ImportError:
    HTTP_AVAILABLE = False

# -- Color palette --
COLORS = {
    "bg": "#0f0f1a",
    "surface": "#1a1a2e",
    "surface_hover": "#22223a",
    "border": "#2a2a4a",
    "border_light": "#3a3a5a",
    "accent": "#00b4d8",
    "accent_dim": "#0077b6",
    "accent_glow": "#00d4ff",
    "text": "#e8e8f0",
    "text_dim": "#8888a0",
    "text_muted": "#555570",
    "green": "#4caf50",
    "green_bg": "#0a3d0a",
    "red": "#ff5252",
    "red_bg": "#3d0a0a",
    "orange": "#ff9800",
    "orange_bg": "#3d2a0a",
    "input_bg": "#12122a",
    "button": "#00b4d8",
    "button_hover": "#33ccee",
    "button_text": "#000000",
    "button_stop": "#ff5252",
    "button_stop_hover": "#ff7777",
}


class RoundedFrame(tk.Canvas):
    """A canvas that draws a rounded rectangle background to simulate a card."""
    def __init__(self, parent, bg_color=None, border_color=None, radius=12, **kwargs):
        bg_color = bg_color or COLORS["surface"]
        border_color = border_color or COLORS["border"]
        super().__init__(parent, bg=COLORS["bg"], highlightthickness=0, **kwargs)
        self._bg_color = bg_color
        self._border_color = border_color
        self._radius = radius
        self._inner = tk.Frame(self, bg=bg_color)
        self.bind("<Configure>", self._draw)

    def _draw(self, event=None):
        self.delete("bg")
        w, h = self.winfo_width(), self.winfo_height()
        r = self._radius
        self.create_rounded_rect(1, 1, w - 1, h - 1, r, fill=self._bg_color, outline=self._border_color, tags="bg")
        self._inner.place(x=r // 2 + 4, y=r // 2 + 4, width=w - r - 8, height=h - r - 8)

    def create_rounded_rect(self, x1, y1, x2, y2, r, **kwargs):
        points = [
            x1 + r, y1, x2 - r, y1, x2, y1, x2, y1 + r,
            x2, y2 - r, x2, y2, x2 - r, y2, x1 + r, y2,
            x1, y2, x1, y2 - r, x1, y1 + r, x1, y1,
        ]
        return self.create_polygon(points, smooth=True, **kwargs)

    @property
    def inner(self):
        return self._inner


class StyledEntry(tk.Entry):
    """Dark-themed entry widget."""
    def __init__(self, parent, **kwargs):
        kwargs.setdefault("bg", COLORS["input_bg"])
        kwargs.setdefault("fg", COLORS["text"])
        kwargs.setdefault("insertbackground", COLORS["accent"])
        kwargs.setdefault("relief", "flat")
        kwargs.setdefault("font", ("Segoe UI", 10))
        kwargs.setdefault("highlightthickness", 1)
        kwargs.setdefault("highlightbackground", COLORS["border"])
        kwargs.setdefault("highlightcolor", COLORS["accent"])
        super().__init__(parent, **kwargs)


class StyledButton(tk.Button):
    """Modern flat button."""
    def __init__(self, parent, accent=True, danger=False, **kwargs):
        if danger:
            bg = COLORS["button_stop"]
            hover = COLORS["button_stop_hover"]
        elif accent:
            bg = COLORS["button"]
            hover = COLORS["button_hover"]
        else:
            bg = COLORS["surface_hover"]
            hover = COLORS["border"]

        kwargs.setdefault("bg", bg)
        kwargs.setdefault("fg", COLORS["button_text"] if accent or danger else COLORS["text"])
        kwargs.setdefault("activebackground", hover)
        kwargs.setdefault("activeforeground", kwargs["fg"])
        kwargs.setdefault("relief", "flat")
        kwargs.setdefault("font", ("Segoe UI", 10, "bold"))
        kwargs.setdefault("cursor", "hand2")
        kwargs.setdefault("bd", 0)
        kwargs.setdefault("padx", 20)
        kwargs.setdefault("pady", 8)
        super().__init__(parent, **kwargs)
        self._bg = bg
        self._hover = hover
        self.bind("<Enter>", lambda e: self.config(bg=self._hover))
        self.bind("<Leave>", lambda e: self.config(bg=self._bg))

    def set_danger(self, danger):
        if danger:
            self._bg = COLORS["button_stop"]
            self._hover = COLORS["button_stop_hover"]
        else:
            self._bg = COLORS["button"]
            self._hover = COLORS["button_hover"]
        self.config(bg=self._bg)


class AudioCastApp:
    def __init__(self, root):
        self.root = root
        self.root.title("AudioCast")
        self.root.configure(bg=COLORS["bg"])
        self.root.minsize(520, 600)
        self.root.geometry("520x720")

        self.create_window_icon()

        self.server_process = None
        self.mode = tk.StringVar(value="server")
        self.running = False
        self.hidden_to_tray = False

        self.server_name = tk.StringVar(value=self._get_hostname())
        self.server_port = tk.IntVar(value=4953)
        self.server_codec = tk.StringVar(value="pcm")
        self.server_pin = tk.StringVar(value="")

        self.receiver_addr = tk.StringVar(value="")
        self.receiver_name = tk.StringVar(value="PC Receiver")

        self.status_text = tk.StringVar(value="Ready")

        # Live data from API
        self.api_data = None
        self.api_sources = None
        self.poll_id = None
        self._peak_poll_id = None

        # Dashboard state tracking
        self._dash_vars = {}       # StringVar references for in-place updates

        # Audio visualizer state
        self._vu_history = [0.0] * 50  # rolling peak history for waveform
        self._vu_canvas = None

        # Connection history
        self._history_file = os.path.join(
            os.path.dirname(os.path.abspath(__file__)) if not getattr(sys, 'frozen', False)
            else os.path.dirname(sys.executable),
            "connection_history.json"
        )
        self._connection_history = self._load_history()

        self.tray_icon = None

        self._build_ui()
        self.setup_tray()

        self.root.after(500, self.start_service)

    # ── Hostname ──────────────────────────────────────────────
    def _get_hostname(self):
        try:
            return subprocess.check_output("hostname", shell=True).decode().strip()
        except Exception:
            return "My PC"

    # ── Window icon ───────────────────────────────────────────
    def create_window_icon(self):
        if Image is None:
            return
        try:
            icon_img = Image.new('RGBA', (32, 32), (15, 15, 26, 255))
            dc = ImageDraw.Draw(icon_img)
            dc.ellipse([(2, 2), (29, 29)], fill=(0, 180, 216))
            dc.ellipse([(8, 8), (24, 24)], fill=(15, 15, 26))
            dc.ellipse([(12, 12), (20, 20)], fill=(0, 180, 216))
            ico_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "audiocast.ico")
            icon_img.save(ico_path, format='ICO', sizes=[(32, 32), (16, 16)])
            self.root.iconbitmap(ico_path)
        except Exception:
            pass

    def create_tray_icon_image(self, width=64, height=64):
        img = Image.new('RGB', (width, height), color=(15, 15, 26))
        dc = ImageDraw.Draw(img)
        dc.ellipse([(8, 8), (width - 8, height - 8)], fill=(0, 180, 216))
        dc.ellipse([(16, 16), (width - 16, height - 16)], fill=(15, 15, 26))
        dc.ellipse([(24, 24), (width - 24, height - 24)], fill=(0, 180, 216))
        return img

    # ── Tray ──────────────────────────────────────────────────
    def setup_tray(self):
        if not PYSTRAY_AVAILABLE:
            return

        def show_window(icon=None, item=None):
            self.root.deiconify()
            self.root.attributes("-topmost", True)
            self.root.attributes("-topmost", False)
            self.hidden_to_tray = False

        def quit_app(icon=None, item=None):
            self.stop_service()
            if self.tray_icon:
                try:
                    self.tray_icon.stop()
                except Exception:
                    pass
            self.root.after(100, self._force_quit)

        def start_server_action(icon=None, item=None):
            if not self.running:
                self.mode.set("server")
                self.start_service()

        def stop_action(icon=None, item=None):
            self.stop_service()

        menu = TrayMenu(
            TrayMenuItem("Show AudioCast", show_window),
            TrayMenuItem("Start Server", start_server_action),
            TrayMenuItem("Stop", stop_action),
            TrayMenuItem("Quit", quit_app),
        )
        self.tray_icon = TrayIcon("AudioCast", self.create_tray_icon_image(), "AudioCast", menu)
        threading.Thread(target=lambda: self.tray_icon and self.tray_icon.run(), daemon=True).start()

    def minimize_to_tray(self):
        self.root.withdraw()
        self.hidden_to_tray = True

    def _force_quit(self):
        try:
            self.root.destroy()
        except Exception:
            pass
        os._exit(0)

    # ── Connection history ─────────────────────────────────────
    def _load_history(self):
        try:
            with open(self._history_file, "r") as f:
                return json.load(f)
        except Exception:
            return []

    def _save_history(self):
        try:
            with open(self._history_file, "w") as f:
                json.dump(self._connection_history, f, indent=2)
        except Exception:
            pass

    def _add_to_history(self, address, name="", mode="server", codec="", port=0):
        # Remove existing entry with same address
        self._connection_history = [
            h for h in self._connection_history if h.get("address") != address
        ]
        # Add to front
        self._connection_history.insert(0, {
            "address": address,
            "name": name,
            "mode": mode,
            "codec": codec,
            "port": port,
            "last_used": time.strftime("%Y-%m-%d %H:%M"),
        })
        # Keep max 10 entries
        self._connection_history = self._connection_history[:10]
        self._save_history()

    def _remove_from_history(self, address):
        self._connection_history = [
            h for h in self._connection_history if h.get("address") != address
        ]
        self._save_history()
        self._show_settings()  # Refresh UI

    # ── UI Construction ───────────────────────────────────────
    def _build_ui(self):
        # Scrollable canvas for the whole window
        self.canvas = tk.Canvas(self.root, bg=COLORS["bg"], highlightthickness=0)
        self.scrollbar = tk.Scrollbar(self.root, orient="vertical", command=self.canvas.yview,
                                       bg=COLORS["surface"], troughcolor=COLORS["bg"])
        self.canvas.configure(yscrollcommand=self.scrollbar.set)

        self.scrollbar.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)

        self.main_frame = tk.Frame(self.canvas, bg=COLORS["bg"])
        self.canvas_window = self.canvas.create_window((0, 0), window=self.main_frame, anchor="nw")

        self.main_frame.bind("<Configure>", lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", self._on_canvas_resize)
        self.canvas.bind_all("<MouseWheel>", lambda e: self.canvas.yview_scroll(-1 * (e.delta // 120), "units"))

        pad = 20

        # ── Header ──
        header = tk.Frame(self.main_frame, bg=COLORS["bg"])
        header.pack(fill="x", padx=pad, pady=(pad, 5))

        tk.Label(header, text="AudioCast",
                 font=("Segoe UI", 22, "bold"), fg=COLORS["accent_glow"],
                 bg=COLORS["bg"]).pack(side="left")

        if PYSTRAY_AVAILABLE:
            tray_btn = tk.Label(header, text="—", font=("Segoe UI", 14), fg=COLORS["text_dim"],
                                bg=COLORS["bg"], cursor="hand2")
            tray_btn.pack(side="right", padx=(0, 4))
            tray_btn.bind("<Button-1>", lambda e: self.minimize_to_tray())

        tk.Label(header, text="LAN Audio Streaming",
                 font=("Segoe UI", 9), fg=COLORS["text_muted"],
                 bg=COLORS["bg"]).pack(side="left", padx=(10, 0), pady=(8, 0))

        # ── Separator ──
        tk.Frame(self.main_frame, bg=COLORS["border"], height=1).pack(fill="x", padx=pad, pady=(5, 15))

        # ── Mode toggle ──
        mode_frame = tk.Frame(self.main_frame, bg=COLORS["bg"])
        mode_frame.pack(fill="x", padx=pad, pady=(0, 12))

        self.mode_btns = {}
        for val, label in [("server", "Server"), ("receiver", "Receiver")]:
            btn = tk.Label(mode_frame, text=label, font=("Segoe UI", 11, "bold"),
                           padx=24, pady=6, cursor="hand2")
            btn.pack(side="left", padx=(0, 6))
            btn.bind("<Button-1>", lambda e, v=val: self._set_mode(v))
            self.mode_btns[val] = btn
        self._update_mode_buttons()

        # ── Settings card ──
        self.settings_container = tk.Frame(self.main_frame, bg=COLORS["bg"])
        self.settings_container.pack(fill="x", padx=pad, pady=(0, 12))
        self._show_settings()

        # ── Control button ──
        btn_frame = tk.Frame(self.main_frame, bg=COLORS["bg"])
        btn_frame.pack(fill="x", padx=pad, pady=(0, 12))
        self.start_btn = StyledButton(btn_frame, text="Start Server", command=self.toggle_service)
        self.start_btn.pack(fill="x", ipady=4)

        # ── Status bar ──
        self.status_bar = tk.Frame(self.main_frame, bg=COLORS["surface"], height=36)
        self.status_bar.pack(fill="x", padx=pad, pady=(0, 12))
        self.status_bar.pack_propagate(False)

        self.status_dot = tk.Canvas(self.status_bar, width=10, height=10,
                                    bg=COLORS["surface"], highlightthickness=0)
        self.status_dot.pack(side="left", padx=(12, 6), pady=12)
        self._draw_status_dot(COLORS["text_muted"])

        self.status_label = tk.Label(self.status_bar, textvariable=self.status_text,
                                     font=("Segoe UI", 9), fg=COLORS["text_dim"],
                                     bg=COLORS["surface"], anchor="w")
        self.status_label.pack(side="left", fill="x", expand=True)

        # ── Dashboard (live info shown when server is running) ──
        self.dashboard_frame = tk.Frame(self.main_frame, bg=COLORS["bg"])
        self.dashboard_frame.pack(fill="x", padx=pad, pady=(0, 12))
        # Will be populated by _update_dashboard

        # ── Footer ──
        tk.Label(self.main_frame, text="Close window to minimize to tray",
                 font=("Segoe UI", 8), fg=COLORS["text_muted"],
                 bg=COLORS["bg"]).pack(pady=(4, pad))

    def _on_canvas_resize(self, event):
        self.canvas.itemconfig(self.canvas_window, width=event.width)

    # ── Mode switching ────────────────────────────────────────
    def _set_mode(self, mode):
        if self.running:
            messagebox.showwarning("Warning", "Stop the service before switching modes.")
            return
        self.mode.set(mode)
        self._update_mode_buttons()
        self._show_settings()
        if not self.running:
            self.start_btn.config(text="Start Server" if mode == "server" else "Connect")

    def _update_mode_buttons(self):
        for val, btn in self.mode_btns.items():
            if val == self.mode.get():
                btn.config(bg=COLORS["accent"], fg=COLORS["button_text"])
            else:
                btn.config(bg=COLORS["surface"], fg=COLORS["text_dim"])

    # ── Settings forms ────────────────────────────────────────
    def _show_settings(self):
        for w in self.settings_container.winfo_children():
            w.destroy()

        card = tk.Frame(self.settings_container, bg=COLORS["surface"],
                        highlightbackground=COLORS["border"], highlightthickness=1)
        card.pack(fill="x")

        inner = tk.Frame(card, bg=COLORS["surface"])
        inner.pack(fill="x", padx=16, pady=14)

        if self.mode.get() == "server":
            self._card_heading(inner, "Server Settings")
            self._field_row(inner, "Server Name", self.server_name, 0)
            self._field_row(inner, "Port", self.server_port, 1, width=10)

            # Codec selector
            row = 2
            tk.Label(inner, text="Codec", font=("Segoe UI", 9), fg=COLORS["text_dim"],
                     bg=COLORS["surface"]).grid(row=row, column=0, sticky="w", pady=(8, 2))
            codec_frame = tk.Frame(inner, bg=COLORS["surface"])
            codec_frame.grid(row=row, column=1, sticky="w", pady=(8, 2), padx=(8, 0))
            for codec_val, codec_label in [("pcm", "PCM"), ("opus", "Opus"), ("flac", "FLAC")]:
                cb = tk.Radiobutton(codec_frame, text=codec_label, variable=self.server_codec,
                                    value=codec_val, font=("Segoe UI", 9),
                                    fg=COLORS["text"], bg=COLORS["surface"],
                                    selectcolor=COLORS["input_bg"],
                                    activebackground=COLORS["surface"],
                                    activeforeground=COLORS["accent"],
                                    highlightthickness=0, bd=0)
                cb.pack(side="left", padx=(0, 12))

            # PIN
            row = 3
            tk.Label(inner, text="PIN", font=("Segoe UI", 9), fg=COLORS["text_dim"],
                     bg=COLORS["surface"]).grid(row=row, column=0, sticky="w", pady=(8, 2))
            pin_frame = tk.Frame(inner, bg=COLORS["surface"])
            pin_frame.grid(row=row, column=1, sticky="w", pady=(8, 2), padx=(8, 0))
            StyledEntry(pin_frame, textvariable=self.server_pin, width=14, show="*").pack(side="left")
            tk.Label(pin_frame, text="optional", font=("Segoe UI", 8), fg=COLORS["text_muted"],
                     bg=COLORS["surface"]).pack(side="left", padx=(8, 0))
        else:
            self._card_heading(inner, "Receiver Settings")
            self._field_row(inner, "Device Name", self.receiver_name, 0)
            self._field_row(inner, "Server Address", self.receiver_addr, 1)
            tk.Label(inner, text="e.g. 192.168.1.100:4953", font=("Segoe UI", 8),
                     fg=COLORS["text_muted"], bg=COLORS["surface"]).grid(
                         row=3, column=1, sticky="w", padx=(8, 0))

            # Connection history
            history = self._connection_history
            if history:
                hist_card = tk.Frame(self.settings_container, bg=COLORS["surface"],
                                     highlightbackground=COLORS["border"], highlightthickness=1)
                hist_card.pack(fill="x", pady=(8, 0))
                hist_inner = tk.Frame(hist_card, bg=COLORS["surface"])
                hist_inner.pack(fill="x", padx=14, pady=12)

                tk.Label(hist_inner, text="Recent Connections", font=("Segoe UI", 10, "bold"),
                         fg=COLORS["text"], bg=COLORS["surface"]).pack(anchor="w", pady=(0, 8))

                for hi, h in enumerate(history):
                    hf = tk.Frame(hist_inner, bg=COLORS["surface"], cursor="hand2")
                    hf.pack(fill="x", pady=2)

                    # Left side: info
                    left = tk.Frame(hf, bg=COLORS["surface"], cursor="hand2")
                    left.pack(side="left", fill="x", expand=True)

                    addr = h.get("address", "")
                    name = h.get("name", "")
                    display_name = name if name else addr
                    tk.Label(left, text=display_name, font=("Segoe UI", 9, "bold"),
                             fg=COLORS["accent_glow"], bg=COLORS["surface"],
                             cursor="hand2").pack(anchor="w")

                    detail_parts = [addr]
                    if h.get("codec"):
                        detail_parts.append(h["codec"].upper())
                    if h.get("last_used"):
                        detail_parts.append(h["last_used"])
                    tk.Label(left, text="  |  ".join(detail_parts),
                             font=("Segoe UI", 8), fg=COLORS["text_dim"],
                             bg=COLORS["surface"], cursor="hand2").pack(anchor="w")

                    # Right side: delete button
                    del_btn = tk.Label(hf, text="x", font=("Segoe UI", 9),
                                       fg=COLORS["text_muted"], bg=COLORS["surface"],
                                       cursor="hand2", padx=6)
                    del_btn.pack(side="right")
                    del_btn.bind("<Button-1>", lambda e, a=addr: self._remove_from_history(a))

                    # Click row to connect
                    def on_click(e, address=addr):
                        self.receiver_addr.set(address)
                    for widget in [hf, left]:
                        widget.bind("<Button-1>", on_click)
                    for child in left.winfo_children():
                        child.bind("<Button-1>", on_click)

                    if hi < len(history) - 1:
                        tk.Frame(hist_inner, bg=COLORS["border"], height=1).pack(fill="x", pady=2)

    def _card_heading(self, parent, text):
        tk.Label(parent, text=text, font=("Segoe UI", 11, "bold"), fg=COLORS["text"],
                 bg=COLORS["surface"]).grid(row=0, column=0, columnspan=2, sticky="w", pady=(0, 8))

    def _field_row(self, parent, label, variable, row_offset, width=28):
        r = row_offset + 1  # +1 because heading is row 0
        tk.Label(parent, text=label, font=("Segoe UI", 9), fg=COLORS["text_dim"],
                 bg=COLORS["surface"]).grid(row=r, column=0, sticky="w", pady=(8, 2))
        StyledEntry(parent, textvariable=variable, width=width).grid(
            row=r, column=1, sticky="w", pady=(8, 2), padx=(8, 0))

    # ── Status dot ────────────────────────────────────────────
    def _draw_status_dot(self, color):
        self.status_dot.delete("all")
        self.status_dot.create_oval(1, 1, 9, 9, fill=color, outline=color)

    # ── Service control ───────────────────────────────────────
    def toggle_service(self):
        if self.running:
            self.stop_service()
        else:
            self.start_service()

    def start_service(self):
        if self.running:
            return

        # When frozen by PyInstaller, look next to the .exe; otherwise next to the .py
        if getattr(sys, 'frozen', False):
            app_dir = os.path.dirname(sys.executable)
        else:
            app_dir = os.path.dirname(os.path.abspath(__file__))

        search_paths = [
            os.path.join(app_dir, "audiocast-server.exe"),                          # installed layout
            os.path.join(app_dir, "server", "target", "release", "audiocast-server.exe"),  # dev layout
            os.path.join(app_dir, "AudioCasting", "server", "target", "release", "audiocast-server.exe"),
        ]

        exe_path = None
        for p in search_paths:
            if os.path.exists(p):
                exe_path = p
                break

        if exe_path is None:
            messagebox.showerror("Error",
                "Could not find audiocast-server.exe\n\nBuild the server first:\n  cd server && cargo build --release")
            return

        if self.mode.get() == "server":
            cmd = [exe_path, "--name", self.server_name.get(),
                   "--port", str(self.server_port.get()),
                   "--codec", self.server_codec.get(), "--no-tray"]
            if self.server_pin.get():
                cmd.extend(["--pin", self.server_pin.get()])
            self.status_text.set("Starting server...")
        else:
            if not self.receiver_addr.get():
                messagebox.showerror("Error", "Please enter server address")
                return
            cmd = [exe_path, "--mode", "receive",
                   "--connect", self.receiver_addr.get(),
                   "--client-name", self.receiver_name.get()]
            self.status_text.set("Connecting...")

        try:
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            startupinfo.wShowWindow = subprocess.SW_HIDE

            self.server_process = subprocess.Popen(
                cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                cwd=os.path.dirname(exe_path),
                startupinfo=startupinfo,
                creationflags=subprocess.CREATE_NO_WINDOW if platform.system() == "Windows" else 0
            )
            self.running = True
            self.start_btn.config(text="Stop Server" if self.mode.get() == "server" else "Disconnect")
            self.start_btn.set_danger(True)
            self._draw_status_dot(COLORS["green"])

            if self.mode.get() == "server":
                web_port = self.server_port.get() + 1
                self.status_text.set(f"Server running on port {self.server_port.get()}")
                self._start_polling(web_port)
                self._add_to_history(
                    f"localhost:{self.server_port.get()}",
                    name=self.server_name.get(), mode="server",
                    codec=self.server_codec.get(), port=self.server_port.get())
            else:
                self.status_text.set(f"Connected to {self.receiver_addr.get()}")
                self._add_to_history(
                    self.receiver_addr.get(),
                    name=self.receiver_name.get(), mode="receiver")

            threading.Thread(target=self._watch_process, daemon=True).start()
        except Exception as e:
            messagebox.showerror("Error", f"Failed to start: {e}")
            self.status_text.set("Error")

    def stop_service(self):
        self._stop_polling()
        self._stop_peak_polling()
        if self.server_process:
            try:
                self.server_process.terminate()
                self.server_process.wait(timeout=3)
            except Exception:
                try:
                    self.server_process.kill()
                except Exception:
                    pass
            self.server_process = None
        self.running = False
        self.start_btn.config(text="Start Server" if self.mode.get() == "server" else "Connect")
        self.start_btn.set_danger(False)
        self._draw_status_dot(COLORS["text_muted"])
        self.status_text.set("Stopped")
        self.api_data = None
        self.api_sources = None
        self._dash_vars = {}
        self._vu_history = [0.0] * 50
        self._vu_canvas = None
        for w in self.dashboard_frame.winfo_children():
            w.destroy()

    def _watch_process(self):
        if self.server_process:
            self.server_process.wait()
        if self.running:
            self.running = False
            self.root.after(0, self._on_process_exit)

    def _on_process_exit(self):
        self._stop_polling()
        self._stop_peak_polling()
        self.running = False
        self.start_btn.config(text="Start Server" if self.mode.get() == "server" else "Connect")
        self.start_btn.set_danger(False)
        self._draw_status_dot(COLORS["red"])
        self.status_text.set("Service stopped unexpectedly")
        self.api_data = None
        self._dash_vars = {}
        self._vu_history = [0.0] * 50
        self._vu_canvas = None
        for w in self.dashboard_frame.winfo_children():
            w.destroy()

    # ── API polling ───────────────────────────────────────────
    def _start_polling(self, web_port):
        self._web_port = web_port
        self._poll_api()

    def _stop_polling(self):
        if self.poll_id:
            self.root.after_cancel(self.poll_id)
            self.poll_id = None

    def _poll_api(self):
        if not self.running or not HTTP_AVAILABLE:
            return

        def fetch():
            data = None
            sources = None
            try:
                url = f"http://localhost:{self._web_port}/api/status"
                req = urllib.request.Request(url, headers={"Accept": "application/json"})
                with urllib.request.urlopen(req, timeout=2) as resp:
                    data = json.loads(resp.read().decode())
            except Exception:
                pass

            try:
                url2 = f"http://localhost:{self._web_port}/api/sources"
                req2 = urllib.request.Request(url2, headers={"Accept": "application/json"})
                with urllib.request.urlopen(req2, timeout=2) as resp2:
                    sources = json.loads(resp2.read().decode())
            except Exception:
                pass

            # Single combined update on main thread
            if data is not None:
                self.root.after(0, lambda: self._on_api_update(data, sources))

        threading.Thread(target=fetch, daemon=True).start()
        self.poll_id = self.root.after(2000, self._poll_api)

    def _on_api_update(self, data, sources):
        self.api_data = data
        if sources is not None:
            self.api_sources = sources
        self._update_dashboard()

    # ── Dashboard: update ────────────────────────────────────
    def _update_dashboard(self):
        data = self.api_data
        if data is None:
            return
        sources = self.api_sources

        # Build section containers once (never destroyed while running)
        if not self._dash_vars.get("_built"):
            self._build_dashboard_skeleton()

        # Update each section — only rebuild a section if its structure changed
        self._update_info_section(data)
        self._update_clients_section(data)
        self._update_groups_section(data)
        self._update_sources_section(data, sources)

    def _build_dashboard_skeleton(self):
        """Create the permanent section containers. Called once."""
        self._dash_vars["_built"] = True

        # Each section gets a stable container frame that is never destroyed
        self._info_container = tk.Frame(self.dashboard_frame, bg=COLORS["bg"])
        self._info_container.pack(fill="x", pady=(0, 10))

        # Audio visualizer (VU meter)
        self._vu_container = tk.Frame(self.dashboard_frame, bg=COLORS["bg"])
        self._vu_container.pack(fill="x", pady=(0, 10))
        self._build_vu_meter()

        self._clients_container = tk.Frame(self.dashboard_frame, bg=COLORS["bg"])
        self._clients_container.pack(fill="x", pady=(0, 10))

        self._groups_container = tk.Frame(self.dashboard_frame, bg=COLORS["bg"])
        self._groups_container.pack(fill="x", pady=(0, 10))

        self._sources_container = tk.Frame(self.dashboard_frame, bg=COLORS["bg"])
        self._sources_container.pack(fill="x", pady=(0, 10))

        # Web UI link (static, never changes)
        if self.running and self.mode.get() == "server":
            link_frame = tk.Frame(self.dashboard_frame, bg=COLORS["bg"])
            link_frame.pack(fill="x", pady=(4, 0))
            web_url = f"http://localhost:{self._web_port}"
            link = tk.Label(link_frame, text=f"Web UI: {web_url}",
                            font=("Segoe UI", 9, "underline"), fg=COLORS["accent"],
                            bg=COLORS["bg"], cursor="hand2")
            link.pack(anchor="w")
            link.bind("<Button-1>", lambda e: os.startfile(web_url) if hasattr(os, 'startfile') else None)

        # Track shapes per section
        self._info_built = False
        self._clients_shape = None
        self._groups_shape = None
        self._sources_shape = None

    # ── Audio Visualizer (VU Meter) ─────────────────────────
    def _build_vu_meter(self):
        outer = tk.Frame(self._vu_container, bg=COLORS["surface"],
                         highlightbackground=COLORS["border"], highlightthickness=1)
        outer.pack(fill="x")
        inner = tk.Frame(outer, bg=COLORS["surface"])
        inner.pack(fill="x", padx=14, pady=12)

        # Title row with dB label
        title_row = tk.Frame(inner, bg=COLORS["surface"])
        title_row.pack(fill="x", pady=(0, 6))
        tk.Label(title_row, text="Audio Level", font=("Segoe UI", 10, "bold"),
                 fg=COLORS["text"], bg=COLORS["surface"]).pack(side="left")
        self._vu_db_var = tk.StringVar(value="-- dB")
        tk.Label(title_row, textvariable=self._vu_db_var, font=("Segoe UI", 9),
                 fg=COLORS["text_dim"], bg=COLORS["surface"]).pack(side="right")

        # VU bar (horizontal level meter)
        self._vu_bar = tk.Canvas(inner, height=12, bg=COLORS["input_bg"], highlightthickness=0)
        self._vu_bar.pack(fill="x", pady=(0, 6))

        # Waveform canvas (rolling history)
        self._vu_canvas = tk.Canvas(inner, height=48, bg=COLORS["input_bg"], highlightthickness=0)
        self._vu_canvas.pack(fill="x")

        # Start fast polling for peak level
        self._start_peak_polling()

    def _start_peak_polling(self):
        self._poll_peak()

    def _stop_peak_polling(self):
        if self._peak_poll_id:
            self.root.after_cancel(self._peak_poll_id)
            self._peak_poll_id = None

    def _poll_peak(self):
        if not self.running or not HTTP_AVAILABLE:
            return

        def fetch_peak():
            try:
                url = f"http://localhost:{self._web_port}/api/status"
                req = urllib.request.Request(url, headers={"Accept": "application/json"})
                with urllib.request.urlopen(req, timeout=1) as resp:
                    data = json.loads(resp.read().decode())
                peak = data.get("peak_level", 0.0)
                self.root.after(0, lambda: self._update_vu(peak))
            except Exception:
                pass

        threading.Thread(target=fetch_peak, daemon=True).start()
        self._peak_poll_id = self.root.after(150, self._poll_peak)

    def _update_vu(self, peak):
        # Update rolling history
        self._vu_history.append(peak)
        self._vu_history = self._vu_history[-50:]

        # dB display
        if peak > 0.0001:
            import math
            db = 20 * math.log10(peak)
            self._vu_db_var.set(f"{db:.1f} dB")
        else:
            self._vu_db_var.set("-inf dB")

        # Draw VU bar
        bar = self._vu_bar
        bar.delete("all")
        w = bar.winfo_width()
        h = bar.winfo_height()
        if w > 1:
            bar_w = int(peak * w)
            # Color gradient: green -> yellow -> red
            if peak < 0.5:
                color = COLORS["green"]
            elif peak < 0.8:
                color = COLORS["orange"]
            else:
                color = COLORS["red"]
            bar.create_rectangle(0, 0, bar_w, h, fill=color, outline="")
            # Peak markers at -6dB (0.5) and -3dB (0.7)
            for marker in [0.5, 0.7, 0.9]:
                mx = int(marker * w)
                bar.create_line(mx, 0, mx, h, fill=COLORS["border_light"], width=1)

        # Draw waveform
        cv = self._vu_canvas
        cv.delete("all")
        cw = cv.winfo_width()
        ch = cv.winfo_height()
        if cw > 1 and len(self._vu_history) > 1:
            points = []
            step = cw / (len(self._vu_history) - 1)
            for i, val in enumerate(self._vu_history):
                x = i * step
                y = ch - (val * ch * 0.9) - 2
                points.append(x)
                points.append(y)

            if len(points) >= 4:
                cv.create_line(points, fill=COLORS["accent"], width=2, smooth=True)
                # Fill under the curve
                fill_points = [0, ch] + points + [cw, ch]
                cv.create_polygon(fill_points, fill=COLORS["accent_dim"],
                                  outline="", stipple="gray25")

    # ── Info section (built once, never changes structure) ────
    def _update_info_section(self, data):
        v = self._dash_vars
        if not self._info_built:
            self._info_built = True
            card = self._make_card(self._info_container, "Audio Stream")
            card.pack(fill="x")

            info_grid = tk.Frame(card, bg=COLORS["surface"])
            info_grid.pack(fill="x")

            stat_keys = ["codec", "sample_rate", "channels", "bitrate", "buffer_ms", "pin_auth"]
            stat_labels = ["Codec", "Sample Rate", "Channels", "Bitrate", "Buffer", "PIN Auth"]

            for i, (key, label) in enumerate(zip(stat_keys, stat_labels)):
                col = i % 3
                row = i // 3
                cell = tk.Frame(info_grid, bg=COLORS["surface"])
                cell.grid(row=row, column=col, sticky="ew", padx=(0, 12), pady=4)
                tk.Label(cell, text=label, font=("Segoe UI", 8), fg=COLORS["text_muted"],
                         bg=COLORS["surface"]).pack(anchor="w")
                sv = tk.StringVar()
                v[f"stat_{key}"] = sv
                tk.Label(cell, textvariable=sv, font=("Segoe UI", 11, "bold"), fg=COLORS["text"],
                         bg=COLORS["surface"]).pack(anchor="w")
            info_grid.columnconfigure(0, weight=1)
            info_grid.columnconfigure(1, weight=1)
            info_grid.columnconfigure(2, weight=1)

        # Update values
        v["stat_codec"].set(data.get("codec", "-").upper())
        v["stat_sample_rate"].set(f"{data.get('sample_rate', '-')} Hz")
        v["stat_channels"].set("Stereo" if data.get("channels") == 2 else "Mono")
        v["stat_bitrate"].set(f"{data.get('bitrate', '-')} kbps")
        v["stat_buffer_ms"].set(f"{data.get('buffer_ms', '-')} ms")
        v["stat_pin_auth"].set("Enabled" if data.get("requires_pin") else "Disabled")

    # ── Clients section (rebuild only when client list changes) ──
    def _update_clients_section(self, data):
        v = self._dash_vars
        clients = data.get("clients", [])
        client_ids = tuple(c.get("client_id", "") for c in clients)

        if client_ids != self._clients_shape:
            self._clients_shape = client_ids
            # Rebuild only this section's content
            for w in self._clients_container.winfo_children():
                w.destroy()
            # Remove old client vars
            for key in [k for k in v if k.startswith("client_")]:
                del v[key]

            sv_title = tk.StringVar()
            v["clients_title"] = sv_title
            card = self._make_card(self._clients_container, "", title_var=sv_title)
            card.pack(fill="x")

            if not clients:
                tk.Label(card, text="No clients connected",
                         font=("Segoe UI", 9), fg=COLORS["text_muted"],
                         bg=COLORS["surface"]).pack(pady=8)
            else:
                for ci, c in enumerate(clients):
                    cid = c.get("client_id", str(ci))
                    client_row = tk.Frame(card, bg=COLORS["surface"])
                    client_row.pack(fill="x", pady=(0, 6))

                    left = tk.Frame(client_row, bg=COLORS["surface"])
                    left.pack(side="left", fill="x", expand=True)

                    name_frame = tk.Frame(left, bg=COLORS["surface"])
                    name_frame.pack(anchor="w")

                    dot = tk.Canvas(name_frame, width=8, height=8, bg=COLORS["surface"], highlightthickness=0)
                    dot.pack(side="left", padx=(0, 6), pady=2)
                    dot.create_oval(0, 0, 8, 8, fill=COLORS["green"], outline=COLORS["green"])

                    sv_name = tk.StringVar()
                    v[f"client_{cid}_name"] = sv_name
                    tk.Label(name_frame, textvariable=sv_name,
                             font=("Segoe UI", 10, "bold"), fg=COLORS["accent_glow"],
                             bg=COLORS["surface"]).pack(side="left")

                    sv_group = tk.StringVar()
                    v[f"client_{cid}_group"] = sv_group
                    tk.Label(name_frame, textvariable=sv_group,
                             font=("Segoe UI", 8), fg=COLORS["accent_dim"],
                             bg=COLORS["surface"]).pack(side="left")

                    sv_detail = tk.StringVar()
                    v[f"client_{cid}_detail"] = sv_detail
                    tk.Label(left, textvariable=sv_detail, font=("Segoe UI", 8),
                             fg=COLORS["text_dim"], bg=COLORS["surface"]).pack(anchor="w", padx=(14, 0))

                    if ci < len(clients) - 1:
                        tk.Frame(card, bg=COLORS["border"], height=1).pack(fill="x", pady=(4, 2))

        # Update values
        client_count = data.get("client_count", 0)
        v["clients_title"].set(f"Connected Clients ({client_count})")

        for ci, c in enumerate(clients):
            cid = c.get("client_id", str(ci))
            sv = v.get(f"client_{cid}_name")
            if sv:
                sv.set(c.get("client_name", "Unknown"))
            sv = v.get(f"client_{cid}_group")
            if sv:
                group = c.get("volume_group")
                sv.set(f"  [{group}]" if group else "")
            sv = v.get(f"client_{cid}_detail")
            if sv:
                addr = c.get("addr", "")
                vol = f"{int(c.get('volume', 0) * 100)}%"
                rtt = c.get("clock_rtt_us")
                rtt_str = f"{rtt} \u00b5s" if rtt is not None else "syncing"
                sv.set(f"{addr}  |  Vol: {vol}  |  RTT: {rtt_str}")

    # ── Groups section (rebuild only when group list changes) ──
    def _update_groups_section(self, data):
        v = self._dash_vars
        groups = data.get("volume_groups", [])
        group_names = tuple(g.get("name", "") for g in groups)

        if group_names != self._groups_shape:
            self._groups_shape = group_names
            for w in self._groups_container.winfo_children():
                w.destroy()
            for key in [k for k in v if k.startswith("group_")]:
                del v[key]

            if groups:
                card = self._make_card(self._groups_container, "Volume Groups")
                card.pack(fill="x")
                for g in groups:
                    gname = g["name"]
                    gf = tk.Frame(card, bg=COLORS["surface"])
                    gf.pack(fill="x", pady=2)
                    tk.Label(gf, text=gname, font=("Segoe UI", 10, "bold"),
                             fg=COLORS["accent"], bg=COLORS["surface"]).pack(side="left")
                    sv_ginfo = tk.StringVar()
                    v[f"group_{gname}_info"] = sv_ginfo
                    tk.Label(gf, textvariable=sv_ginfo,
                             font=("Segoe UI", 9), fg=COLORS["text_dim"],
                             bg=COLORS["surface"]).pack(side="right")

        for g in groups:
            gname = g["name"]
            sv = v.get(f"group_{gname}_info")
            if sv:
                vol_pct = int(g.get("volume", 0) * 100)
                sv.set(f"{vol_pct}%  ({g.get('member_count', 0)} devices)")

    # ── Sources section (rebuild only when source PIDs change) ──
    def _update_sources_section(self, data, sources):
        v = self._dash_vars
        source_list = (sources or [])[:10]
        source_pids = tuple(s.get("pid", 0) for s in source_list)

        if source_pids != self._sources_shape:
            self._sources_shape = source_pids
            for w in self._sources_container.winfo_children():
                w.destroy()
            for key in [k for k in v if k.startswith("src_")]:
                del v[key]

            if source_list:
                card = self._make_card(self._sources_container, "Audio Sources")
                card.pack(fill="x")
                for s in source_list:
                    pid = s.get("pid", 0)
                    sf = tk.Frame(card, bg=COLORS["surface"], cursor="hand2")
                    sf.pack(fill="x", pady=1)

                    src_dot = tk.Canvas(sf, width=6, height=6, bg=COLORS["surface"], highlightthickness=0)
                    src_dot.pack(side="left", padx=(0, 8), pady=6)
                    v[f"src_{pid}_dot"] = src_dot

                    sv_src_name = tk.StringVar()
                    v[f"src_{pid}_name"] = sv_src_name
                    src_lbl = tk.Label(sf, textvariable=sv_src_name, font=("Segoe UI", 9),
                                       bg=COLORS["surface"], anchor="w")
                    src_lbl.pack(side="left", fill="x", expand=True)
                    v[f"src_{pid}_lbl"] = src_lbl

                    tk.Label(sf, text=f"PID {pid}", font=("Segoe UI", 8),
                             fg=COLORS["text_muted"], bg=COLORS["surface"]).pack(side="right")

                    for widget in [sf, src_dot]:
                        widget.bind("<Button-1>", lambda e, p=pid: self._set_audio_source(p))

        # Update values
        current_pid = data.get("source_pid", 0)
        for s in source_list:
            pid = s.get("pid", 0)
            is_active = pid == current_pid

            name = s.get("name", "Unknown")
            title = s.get("window_title", "")
            display = f"{name}" + (f" - {title}" if title else "")
            sv = v.get(f"src_{pid}_name")
            if sv:
                sv.set(display)

            dot_canvas = v.get(f"src_{pid}_dot")
            if dot_canvas and isinstance(dot_canvas, tk.Canvas):
                color = COLORS["accent"] if is_active else COLORS["text_muted"]
                dot_canvas.delete("all")
                dot_canvas.create_oval(0, 0, 6, 6, fill=color, outline=color)

            lbl = v.get(f"src_{pid}_lbl")
            if lbl and isinstance(lbl, tk.Label):
                lbl.config(fg=COLORS["accent_glow"] if is_active else COLORS["text"])

    def _make_card(self, parent, title, title_var=None):
        """Create a styled card frame with a title (static text or StringVar)."""
        outer = tk.Frame(parent, bg=COLORS["surface"],
                         highlightbackground=COLORS["border"], highlightthickness=1)
        inner = tk.Frame(outer, bg=COLORS["surface"])
        inner.pack(fill="x", padx=14, pady=12)

        if title_var:
            title_var.set(title)
            tk.Label(inner, textvariable=title_var, font=("Segoe UI", 10, "bold"),
                     fg=COLORS["text"], bg=COLORS["surface"]).pack(anchor="w", pady=(0, 8))
        else:
            tk.Label(inner, text=title, font=("Segoe UI", 10, "bold"), fg=COLORS["text"],
                     bg=COLORS["surface"]).pack(anchor="w", pady=(0, 8))

        content = tk.Frame(inner, bg=COLORS["surface"])
        content.pack(fill="x")
        # Return content frame so caller can add widgets
        # But we need outer for packing, so store reference
        content._outer = outer
        content.pack_into = lambda **kw: outer.pack(**kw)

        # Monkey-patch: when caller does card.pack(), pack the outer
        class CardProxy:
            def __init__(self, outer_frame, content_frame):
                self._outer = outer_frame
                self._content = content_frame
            def pack(self, **kw):
                self._outer.pack(**kw)
            def __getattr__(self, name):
                return getattr(self._content, name)
        return CardProxy(outer, content)

    def _set_audio_source(self, pid):
        if not HTTP_AVAILABLE or not self.running:
            return

        def do_set():
            try:
                url = f"http://localhost:{self._web_port}/api/sources/set"
                body = json.dumps({"pid": pid}).encode()
                req = urllib.request.Request(url, data=body,
                                            headers={"Content-Type": "application/json"})
                urllib.request.urlopen(req, timeout=2)
            except Exception:
                pass
        threading.Thread(target=do_set, daemon=True).start()

    # ── Window close ──────────────────────────────────────────
    def on_closing(self):
        if PYSTRAY_AVAILABLE:
            self.minimize_to_tray()
        else:
            if self.running:
                self.stop_service()
            self.root.destroy()


def main():
    # Enable DPI awareness on Windows so the UI renders crisp instead of blurry
    if platform.system() == "Windows":
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)  # Per-monitor DPI aware
        except Exception:
            try:
                ctypes.windll.user32.SetProcessDPIAware()
            except Exception:
                pass

    root = tk.Tk()

    app = AudioCastApp(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    root.mainloop()


if __name__ == "__main__":
    main()
