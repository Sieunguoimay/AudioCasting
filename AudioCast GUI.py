#!/usr/bin/env python3
"""
AudioCast GUI - A simple GUI wrapper for the AudioCast server/receiver
"""

import tkinter as tk
from tkinter import ttk, messagebox
import subprocess
import threading
import os
import sys
import platform

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

class AudioCastApp:
    def __init__(self, root):
        self.root = root
        self.root.title("AudioCast")
        self.root.resizable(False, False)
        
        self.create_window_icon()
        
        self.server_process = None
        self.mode = tk.StringVar(value="server")
        self.running = False
        self.hidden_to_tray = False
        
        self.server_name = tk.StringVar(value=self.get_hostname())
        self.server_port = tk.IntVar(value=4953)
        self.server_codec = tk.StringVar(value="pcm")
        self.server_pin = tk.StringVar(value="")
        
        self.receiver_addr = tk.StringVar(value="")
        self.receiver_name = tk.StringVar(value="PC Receiver")
        
        self.status_text = tk.StringVar(value="Ready")
        
        self.tray_icon = None
        
        self.setup_ui()
        self.setup_tray()
        
        self.root.after(500, self.start_service)
        
    def get_hostname(self):
        try:
            return subprocess.check_output("hostname", shell=True).decode().strip()
        except:
            return "My PC"
    
    def create_window_icon(self):
        if Image is None:
            return
        try:
            icon_img = Image.new('RGBA', (32, 32), (40, 40, 60, 255))
            dc = ImageDraw.Draw(icon_img)
            dc.ellipse([(2, 2), (29, 29)], fill=(0, 180, 255))
            dc.ellipse([(8, 8), (24, 24)], fill=(40, 40, 60))
            dc.ellipse([(12, 12), (20, 20)], fill=(0, 180, 255))
            
            icon_img.save(r'D:\Vuduydu\PersonalProjects\AudioCasting\audiocast.ico', format='ICO', sizes=[(32, 32), (16, 16)])
            
            self.root.iconbitmap(r'D:\Vuduydu\PersonalProjects\AudioCasting\audiocast.ico')
            
            icon_photo = tk.PhotoImage(file=r'D:\Vuduydu\PersonalProjects\AudioCasting\audiocast.ico')
            self.root.iconphoto(True, icon_photo)
        except Exception as e:
            print(f"Could not set window icon: {e}")
    
    def create_tray_icon_image(self, width=64, height=64, color=(0, 180, 255)):
        img = Image.new('RGB', (width, height), color=(40, 40, 60))
        dc = ImageDraw.Draw(img)
        dc.ellipse([(8, 8), (width-8, height-8)], fill=color)
        dc.ellipse([(16, 16), (width-16, height-16)], fill=(40, 40, 60))
        dc.ellipse([(24, 24), (width-24, height-24)], fill=color)
        return img
    
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
                except:
                    pass
            self.root.after(100, self.force_quit)
        
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
        
        def run_tray():
            if self.tray_icon:
                self.tray_icon.run()
        
        self.tray_thread = threading.Thread(target=run_tray, daemon=True)
        self.tray_thread.start()
    
    def force_quit(self):
        try:
            self.root.destroy()
        except:
            pass
        os._exit(0)
    
    def setup_ui(self):
        main_frame = ttk.Frame(self.root, padding="20")
        main_frame.grid(row=0, column=0, sticky="NESW")
        
        title_frame = ttk.Frame(main_frame)
        title_frame.grid(row=0, column=0, columnspan=3, pady=(0, 15))
        
        ttk.Label(title_frame, text="AudioCast", font=("Segoe UI", 16, "bold")).grid(row=0, column=0)
        
        if PYSTRAY_AVAILABLE:
            minimize_btn = ttk.Button(title_frame, text="_", width=3, command=self.minimize_to_tray)
            minimize_btn.grid(row=0, column=1, padx=(10, 0))
        
        mode_frame = ttk.LabelFrame(main_frame, text="Mode", padding="10")
        mode_frame.grid(row=1, column=0, columnspan=3, sticky="EW", pady=(0, 15))
        
        ttk.Radiobutton(mode_frame, text="Server", variable=self.mode, value="server", command=self.on_mode_change).grid(row=0, column=0, padx=10)
        ttk.Radiobutton(mode_frame, text="Receiver", variable=self.mode, value="receiver", command=self.on_mode_change).grid(row=0, column=1, padx=10)
        
        self.settings_frame = ttk.Frame(main_frame)
        self.settings_frame.grid(row=2, column=0, columnspan=3, sticky="EW", pady=(0, 15))
        
        self.server_settings_frame = None
        self.receiver_settings_frame = None
        self.show_server_settings()
        
        self.control_frame = ttk.Frame(main_frame)
        self.control_frame.grid(row=3, column=0, columnspan=3, pady=(0, 15))
        
        self.start_btn = ttk.Button(self.control_frame, text="Start", command=self.toggle_service, width=20)
        self.start_btn.grid(row=0, column=0)
        
        self.status_frame = ttk.LabelFrame(main_frame, text="Status", padding="10")
        self.status_frame.grid(row=4, column=0, columnspan=3, sticky="EW")
        
        ttk.Label(self.status_frame, textvariable=self.status_text, font=("Segoe UI", 10)).grid(row=0, column=0)
        
        ttk.Label(main_frame, text="Minimize to tray to hide this window", font=("Segoe UI", 8), foreground="gray").grid(row=5, column=0, columnspan=3, pady=(10, 0))
    
    def minimize_to_tray(self):
        self.root.withdraw()
        self.hidden_to_tray = True
    
    def on_mode_change(self):
        if self.running:
            messagebox.showwarning("Warning", "Stop the current service before switching modes")
            self.mode.set("server" if self.mode.get() == "receiver" else "receiver")
            return
        if self.mode.get() == "server":
            self.show_server_settings()
        else:
            self.show_receiver_settings()
    
    def show_server_settings(self):
        if self.receiver_settings_frame:
            self.receiver_settings_frame.destroy()
        
        if self.server_settings_frame is None:
            self.server_settings_frame = ttk.Frame(self.settings_frame)
            self.server_settings_frame.grid(row=0, column=0)
        
        for widget in self.server_settings_frame.winfo_children():
            widget.destroy()
        
        ttk.Label(self.server_settings_frame, text="Name:").grid(row=0, column=0, sticky=tk.W, pady=3)
        ttk.Entry(self.server_settings_frame, textvariable=self.server_name, width=25).grid(row=0, column=1, pady=3, padx=5)
        
        ttk.Label(self.server_settings_frame, text="Port:").grid(row=1, column=0, sticky=tk.W, pady=3)
        ttk.Entry(self.server_settings_frame, textvariable=self.server_port, width=10).grid(row=1, column=1, sticky=tk.W, pady=3, padx=5)
        
        ttk.Label(self.server_settings_frame, text="Codec:").grid(row=2, column=0, sticky=tk.W, pady=3)
        codec_frame = ttk.Frame(self.server_settings_frame)
        codec_frame.grid(row=2, column=1, sticky=tk.W, pady=3, padx=5)
        ttk.Radiobutton(codec_frame, text="PCM", variable=self.server_codec, value="pcm").pack(side=tk.LEFT)
        ttk.Radiobutton(codec_frame, text="Opus", variable=self.server_codec, value="opus").pack(side=tk.LEFT)
        ttk.Radiobutton(codec_frame, text="FLAC", variable=self.server_codec, value="flac").pack(side=tk.LEFT)
        
        ttk.Label(self.server_settings_frame, text="PIN:").grid(row=3, column=0, sticky=tk.W, pady=3)
        ttk.Entry(self.server_settings_frame, textvariable=self.server_pin, width=15, show="*").grid(row=3, column=1, sticky=tk.W, pady=3, padx=5)
        ttk.Label(self.server_settings_frame, text="(optional)", font=("Segoe UI", 8), foreground="gray").grid(row=3, column=2, sticky=tk.W)
    
    def show_receiver_settings(self):
        if self.server_settings_frame:
            self.server_settings_frame.destroy()
            self.server_settings_frame = None
        
        if self.receiver_settings_frame is None:
            self.receiver_settings_frame = ttk.Frame(self.settings_frame)
            self.receiver_settings_frame.grid(row=0, column=0)
        
        for widget in self.receiver_settings_frame.winfo_children():
            widget.destroy()
        
        ttk.Label(self.receiver_settings_frame, text="Your name:").grid(row=0, column=0, sticky=tk.W, pady=3)
        ttk.Entry(self.receiver_settings_frame, textvariable=self.receiver_name, width=25).grid(row=0, column=1, pady=3, padx=5)
        
        ttk.Label(self.receiver_settings_frame, text="Server:").grid(row=1, column=0, sticky=tk.W, pady=3)
        ttk.Entry(self.receiver_settings_frame, textvariable=self.receiver_addr, width=25).grid(row=1, column=1, pady=3, padx=5)
        
        ttk.Label(self.receiver_settings_frame, text="e.g. 192.168.1.100:4953", font=("Segoe UI", 8), foreground="gray").grid(row=2, column=0, columnspan=2, sticky=tk.W, padx=5)
    
    def toggle_service(self):
        if self.running:
            self.stop_service()
        else:
            self.start_service()
    
    def start_service(self):
        if self.running:
            self.status_text.set("Already running")
            return
            
        script_dir = os.path.dirname(os.path.abspath(__file__))
        exe_path = os.path.join(script_dir, "server", "target", "release", "audiocast-server.exe")
        
        if not os.path.exists(exe_path):
            alt_path = os.path.join(script_dir, "AudioCasting", "server", "target", "release", "audiocast-server.exe")
            if os.path.exists(alt_path):
                exe_path = alt_path
        
        if not os.path.exists(exe_path):
            for test_path in [
                r"D:\Vuduydu\PersonalProjects\AudioCasting\server\target\release\audiocast-server.exe",
                r"C:\Users\vuduy\Projects\AudioCasting\server\target\release\audiocast-server.exe",
            ]:
                if os.path.exists(test_path):
                    exe_path = test_path
                    break
        
        if not os.path.exists(exe_path):
            messagebox.showerror("Error", "Could not find audiocast-server.exe\n\nPlease build the server first:\ncd server && cargo build --release")
            return
        
        if self.mode.get() == "server":
            cmd = [exe_path, "--name", self.server_name.get(), "--port", str(self.server_port.get()), "--codec", self.server_codec.get(), "--no-tray"]
            if self.server_pin.get():
                cmd.extend(["--pin", self.server_pin.get()])
            self.status_text.set("Server starting...")
        else:
            if not self.receiver_addr.get():
                messagebox.showerror("Error", "Please enter server address")
                return
            cmd = [exe_path, "--mode", "receive", "--connect", self.receiver_addr.get(), "--client-name", self.receiver_name.get()]
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
            self.start_btn.config(text="Stop")
            
            if self.mode.get() == "server":
                web_port = self.server_port.get() + 1
                self.status_text.set(f"Server running - Web UI: http://localhost:{web_port}")
            else:
                self.status_text.set(f"Connected to {self.receiver_addr.get()}")
            
            threading.Thread(target=self.watch_process, daemon=True).start()
        except Exception as e:
            messagebox.showerror("Error", f"Failed to start: {e}")
            self.status_text.set("Error starting service")
    
    def stop_service(self):
        if self.server_process:
            try:
                self.server_process.terminate()
                self.server_process.wait(timeout=3)
            except:
                try:
                    self.server_process.kill()
                except:
                    pass
            self.server_process = None
        self.running = False
        self.start_btn.config(text="Start")
        self.status_text.set("Stopped")
    
    def watch_process(self):
        if self.server_process:
            self.server_process.wait()
        if self.running:
            self.running = False
            self.root.after(0, self.on_process_exit)
    
    def on_process_exit(self):
        self.running = False
        self.start_btn.config(text="Start")
        self.status_text.set("Service stopped")
    
    def on_closing(self):
        if PYSTRAY_AVAILABLE:
            self.minimize_to_tray()
        else:
            if self.running:
                self.stop_service()
            self.root.destroy()

def main():
    root = tk.Tk()
    
    try:
        root.tk.call("tk", "scaling", 1.5)
    except:
        pass
    
    app = AudioCastApp(root)
    root.protocol("WM_DELETE_WINDOW", app.on_closing)
    root.mainloop()

if __name__ == "__main__":
    main()
