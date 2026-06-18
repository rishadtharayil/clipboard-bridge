import tkinter as tk
from tkinter import ttk, messagebox
import random
import string
import json
import os
import threading
import time
import sys
import winreg
from PIL import Image, ImageTk
import qrcode
import pystray
from pystray import MenuItem as item

from clipboard_server import ClipboardServer

def resource_path(relative_path):
    try:
        base_path = sys._MEIPASS
    except Exception:
        base_path = os.path.abspath(".")
    return os.path.join(base_path, relative_path)

CONFIG_FILE = "config.json"

class ClipboardBridgeApp:
    def __init__(self):
        self.server = None
        self.tray_icon = None
        self.history = []
        self.root = None
        self.root_ready = threading.Event()
        self.config = self.load_config()
        
        # Start the server immediately
        self.start_server()
        
        # Start Tkinter in a background thread
        threading.Thread(target=self.run_tk, daemon=True).start()
        
        # Wait for Tkinter to be ready
        self.root_ready.wait()
        
        # Setup System Tray (this will block the main thread)
        self.setup_tray()

    def run_tk(self):
        self.root = tk.Tk()
        self.root_ready.set()
        self.show_window()
        self.root.mainloop()

    def generate_key(self):
        chars = string.ascii_letters + string.digits
        return ''.join(random.choice(chars) for _ in range(16))

    def load_config(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, 'r') as f:
                    config = json.load(f)
                    if 'key' not in config or len(config['key']) != 16:
                        config['key'] = self.generate_key()
                    return config
            except:
                pass
        
        config = {
            'key': self.generate_key(),
            'sync_direction': 'bidirectional',
            'run_on_startup': False
        }
        self.save_config(config)
        return config

    def save_config(self, config=None):
        if config:
            self.config = config
        with open(CONFIG_FILE, 'w') as f:
            json.dump(self.config, f, indent=4)
        
        if self.server:
            self.server.config = self.config

    def set_startup(self, enable):
        key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
        app_name = "ClipboardBridge"
        try:
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_ALL_ACCESS)
            if enable:
                winreg.SetValueEx(key, app_name, 0, winreg.REG_SZ, sys.executable)
            else:
                try:
                    winreg.DeleteValue(key, app_name)
                except FileNotFoundError:
                    pass
            winreg.CloseKey(key)
        except Exception as e:
            print(f"Failed to set startup: {e}")

    def start_server(self):
        if not self.server:
            self.server = ClipboardServer(
                self.config['key'],
                self.config,
                self.log_callback,
                self.status_callback,
                self.history_callback
            )
            self.server.start()

    def stop_server(self):
        if self.server:
            self.server.stop()
            self.server = None
            self.status_callback("Stopped")

    def toggle_pause(self, icon, item):
        if self.server:
            self.stop_server()
        else:
            self.start_server()
        # Update tray menu checkmark implicitly handled by pystray if checked property is bound

    # --- Callbacks ---
    def log_callback(self, message):
        formatted = f"[{time.strftime('%H:%M:%S')}] {message}"
        print(formatted, flush=True)
        if self.root and self.root.winfo_exists():
            self.root.after(0, self._ui_log, formatted)

    def _ui_log(self, text):
        if hasattr(self, 'log_text'):
            self.log_text.config(state=tk.NORMAL)
            self.log_text.insert(tk.END, text + "\n")
            self.log_text.see(tk.END)
            self.log_text.config(state=tk.DISABLED)

    def status_callback(self, status):
        if self.root and self.root.winfo_exists() and hasattr(self, 'status_label'):
            self.root.after(0, lambda: self.status_label.config(
                text=f"Status: {status}",
                fg="#4CAF50" if "Running" in status else "#F44336"
            ))

    def history_callback(self, item):
        self.history.insert(0, f"[{time.strftime('%H:%M:%S')}] {item}")
        if len(self.history) > 10:
            self.history.pop()
        
        if self.root and self.root.winfo_exists() and hasattr(self, 'history_list'):
            self.root.after(0, self._update_history_ui)

    def _update_history_ui(self):
        self.history_list.delete(0, tk.END)
        for h in self.history:
            self.history_list.insert(tk.END, h)

    # --- System Tray ---
    def create_tray_image(self):
        icon_path = resource_path("icon.png")
        if os.path.exists(icon_path):
            try:
                return Image.open(icon_path).resize((64, 64), Image.Resampling.LANCZOS)
            except:
                pass
        img = Image.new('RGB', (64, 64), color="white")
        return img

    def setup_tray(self):
        menu = pystray.Menu(
            item('Show Settings / QR Code', lambda: self.root.after(0, self.show_window), default=True),
            item('Pause Syncing', lambda: self.root.after(0, self.toggle_pause), checked=lambda item: self.server is None),
            item('Run on Startup', self.toggle_startup, checked=lambda item: self.config.get('run_on_startup', False)),
            pystray.Menu.SEPARATOR,
            item('Quit', lambda: self.root.after(0, self.quit_app))
        )
        self.tray_icon = pystray.Icon("ClipboardBridge", self.create_tray_image(), "Clipboard Bridge", menu)
        
        # Start tray on MAIN thread
        self.tray_icon.run()

    def toggle_startup(self, icon, item):
        current = self.config.get('run_on_startup', False)
        self.config['run_on_startup'] = not current
        self.set_startup(not current)
        self.save_config()

    def quit_app(self, icon=None, item=None):
        if self.tray_icon:
            self.tray_icon.stop()
        self.stop_server()
        if self.root and self.root.winfo_exists():
            self.root.destroy()
        os._exit(0)

    # --- GUI ---
    def show_window(self, icon=None, item=None):
        if not hasattr(self, 'tab_settings'):
            self.root.title("Clipboard Bridge Settings")
            self.root.geometry("600x550")
            self.root.configure(bg="#1E1E1E")
            
            icon_path = resource_path("icon.png")
            if os.path.exists(icon_path):
                try:
                    self.win_icon = ImageTk.PhotoImage(Image.open(icon_path).resize((32, 32), Image.Resampling.LANCZOS))
                    self.root.iconphoto(True, self.win_icon)
                except:
                    pass
            
            self.style = ttk.Style()
            self.style.theme_use('clam')
            self.style.configure('TFrame', background='#1E1E1E')
            self.style.configure('TLabel', background='#1E1E1E', foreground='#FFFFFF')
            self.style.configure('TButton', background='#333333', foreground='#FFFFFF')
            
            notebook = ttk.Notebook(self.root)
            notebook.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)
            
            self.tab_settings = ttk.Frame(notebook)
            self.tab_history = ttk.Frame(notebook)
            self.tab_logs = ttk.Frame(notebook)
            
            notebook.add(self.tab_settings, text="Settings & QR")
            notebook.add(self.tab_history, text="History")
            notebook.add(self.tab_logs, text="Logs")
            
            self.build_settings_tab()
            self.build_history_tab()
            self.build_logs_tab()
            
            # Disable closing the app when 'X' is clicked, just hide
            self.root.protocol("WM_DELETE_WINDOW", self.hide_window)
            
            # Initial status
            status = "Running" if self.server else "Stopped"
            self.status_callback(status)
            self._update_history_ui()
            
            self.root.deiconify()
        else:
            self.root.deiconify()
            self.root.lift()

    def hide_window(self):
        if self.root:
            self.root.withdraw()

    def build_settings_tab(self):
        frame = self.tab_settings
        
        self.status_label = tk.Label(frame, text="Status: Running", font=("Segoe UI", 12, "bold"), bg="#1E1E1E", fg="#4CAF50")
        self.status_label.pack(pady=5)

        local_ip = "Unknown"
        if self.server:
            local_ip = self.server.get_local_ip()
        tk.Label(frame, text=f"IP Address: {local_ip}", font=("Segoe UI", 11), bg="#1E1E1E", fg="white").pack(pady=5)
        
        # QR Code
        qr = qrcode.QRCode(box_size=6, border=2)
        qr.add_data(self.config['key'])
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white")
        
        self.qr_photo = ImageTk.PhotoImage(img)
        qr_label = tk.Label(frame, image=self.qr_photo, bg="#1E1E1E")
        qr_label.pack(pady=10)
        
        tk.Label(frame, text=f"Key: {self.config['key']}", font=("Consolas", 14), bg="#1E1E1E", fg="#2196F3").pack()
        
        ttk.Button(frame, text="Regenerate Key", command=self.regenerate_key).pack(pady=10)
        
        # Sync Direction
        tk.Label(frame, text="Sync Direction:", font=("Segoe UI", 10), bg="#1E1E1E", fg="white").pack(pady=(10,0))
        self.direction_var = tk.StringVar(value=self.config.get('sync_direction', 'bidirectional'))
        dirs = [("Bidirectional", "bidirectional"), ("Windows -> Android", "windows_to_android"), ("Android -> Windows", "android_to_windows")]
        for text, val in dirs:
            ttk.Radiobutton(frame, text=text, value=val, variable=self.direction_var, command=self.update_direction).pack()

    def update_direction(self):
        self.config['sync_direction'] = self.direction_var.get()
        self.save_config()

    def regenerate_key(self):
        if messagebox.askyesno("Regenerate Key", "This will disconnect all devices. You will need to scan the new QR code. Continue?"):
            self.config['key'] = self.generate_key()
            self.save_config()
            self.stop_server()
            self.start_server()
            self.hide_window()
            self.show_window() # Reload UI with new QR

    def build_history_tab(self):
        self.history_list = tk.Listbox(self.tab_history, bg="#0A0A0A", fg="#FFFFFF", font=("Segoe UI", 11), bd=0)
        self.history_list.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)

    def build_logs_tab(self):
        self.log_text = tk.Text(self.tab_logs, bg="#0A0A0A", fg="#00FF66", font=("Consolas", 10), state=tk.DISABLED, bd=0)
        self.log_text.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)

if __name__ == "__main__":
    app = ClipboardBridgeApp()
