import tkinter as tk
from tkinter import messagebox
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
import customtkinter as ctk

from clipboard_server import ClipboardServer

if getattr(sys, 'frozen', False):
    APP_DIR = os.path.dirname(sys.executable)
else:
    APP_DIR = os.path.dirname(os.path.abspath(__file__))

def resource_path(relative_path):
    try:
        base_path = sys._MEIPASS
    except Exception:
        base_path = APP_DIR
    return os.path.join(base_path, relative_path)

CONFIG_FILE = os.path.join(APP_DIR, "config.json")

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
        
        # Start CustomTkinter in a background thread
        threading.Thread(target=self.run_tk, daemon=True).start()
        
        # Wait for Tkinter to be ready
        self.root_ready.wait()
        
        # Setup System Tray (this will block the main thread)
        self.setup_tray()

    def run_tk(self):
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("blue")
        
        self.root = ctk.CTk()
        self.root.title("Clipboard Bridge Settings")
        self.root.geometry("700x520")
        self.root.resizable(False, False)
        
        icon_path = resource_path("icon.png")
        if os.path.exists(icon_path):
            try:
                self.win_icon = ImageTk.PhotoImage(Image.open(icon_path).resize((32, 32), Image.Resampling.LANCZOS))
                self.root.iconphoto(True, self.win_icon)
            except:
                pass

        self.build_gui()
        self.root_ready.set()
        
        # Disable closing the app when 'X' is clicked, just hide
        self.root.protocol("WM_DELETE_WINDOW", self.hide_window)
        
        # Initial status
        status = "Running" if self.server else "Stopped"
        self.status_callback(status)
        self._update_history_ui()
        
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
                if getattr(sys, 'frozen', False):
                    cmd = f'"{sys.executable}"'
                else:
                    cmd = f'"{sys.executable}" "{os.path.abspath(sys.argv[0])}"'
                winreg.SetValueEx(key, app_name, 0, winreg.REG_SZ, cmd)
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

    def toggle_pause(self, icon=None, item=None):
        if self.server:
            self.stop_server()
        else:
            self.start_server()

    # --- Callbacks ---
    def log_callback(self, message):
        formatted = f"[{time.strftime('%H:%M:%S')}] {message}"
        print(formatted, flush=True)
        if self.root:
            try:
                self.root.after(0, self._ui_log, formatted)
            except Exception:
                pass

    def _ui_log(self, text):
        if hasattr(self, 'log_text'):
            self.log_text.configure(state=tk.NORMAL)
            self.log_text.insert(tk.END, text + "\n")
            self.log_text.see(tk.END)
            self.log_text.configure(state=tk.DISABLED)

    def status_callback(self, status):
        if self.root:
            try:
                self.root.after(0, self._ui_status_update, status)
            except Exception:
                pass

    def _ui_status_update(self, status):
        self.status_label.configure(text=f"Status: {status}")
        
        # Determine color
        if "Running" in status:
            color = "#10B981"  # Emerald
        elif "Connecting" in status:
            color = "#F59E0B"  # Amber
        else:
            color = "#EF4444"  # Crimson
            
        if hasattr(self, 'status_canvas') and hasattr(self, 'status_dot'):
            self.status_canvas.itemconfig(self.status_dot, fill=color)

    def history_callback(self, item):
        self.history.insert(0, f"[{time.strftime('%H:%M:%S')}] {item}")
        if len(self.history) > 15:
            self.history.pop()
        
        if self.root:
            try:
                self.root.after(0, self._update_history_ui)
            except Exception:
                pass

    def _update_history_ui(self):
        if not hasattr(self, 'history_scrollable'):
            return
            
        # Clear frame
        for widget in self.history_scrollable.winfo_children():
            widget.destroy()
            
        if not self.history:
            no_history = ctk.CTkLabel(
                self.history_scrollable, 
                text="No clipboard items synced yet.", 
                font=ctk.CTkFont(size=13, slant="italic"),
                text_color="#94A3B8"
            )
            no_history.pack(pady=40)
            return

        for item_str in self.history:
            item_frame = ctk.CTkFrame(self.history_scrollable, fg_color="#1E293B", corner_radius=8)
            item_frame.pack(fill=tk.X, pady=4, padx=5)
            
            timestamp = ""
            msg = item_str
            if item_str.startswith("[") and "]" in item_str:
                parts = item_str.split("]", 1)
                timestamp = parts[0] + "]"
                msg = parts[1].strip()
                
            time_lbl = ctk.CTkLabel(item_frame, text=timestamp, font=ctk.CTkFont(size=11), text_color="#94A3B8")
            time_lbl.pack(side=tk.LEFT, padx=10)
            
            content_lbl = ctk.CTkLabel(item_frame, text=msg, font=ctk.CTkFont(size=13), anchor="w")
            content_lbl.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=5)
            
            # Extract text snippet for copy action if possible
            if msg.startswith("Text: "):
                actual_text = msg[6:]
                # Clean ellipsis if present
                if actual_text.endswith("..."):
                    actual_text = actual_text[:-3]
                
                copy_btn = ctk.CTkButton(
                    item_frame, 
                    text="Copy", 
                    width=50, 
                    height=24,
                    font=ctk.CTkFont(size=11),
                    fg_color="#334155",
                    hover_color="#475569",
                    command=lambda t=actual_text: self.copy_to_clipboard_direct(t)
                )
                copy_btn.pack(side=tk.RIGHT, padx=10)

    def copy_to_clipboard_direct(self, val):
        self.root.clipboard_clear()
        self.root.clipboard_append(val)
        self.root.update()

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
            item('Show Settings / QR Code', lambda *args: self.root.after(0, self.show_window), default=True),
            item('Pause Syncing', lambda *args: self.root.after(0, self.toggle_pause), checked=lambda item: self.server is None),
            item('Run on Startup', self.toggle_startup_from_tray, checked=lambda item: self.config.get('run_on_startup', False)),
            pystray.Menu.SEPARATOR,
            item('Quit', lambda *args: self.root.after(0, self.quit_app))
        )
        self.tray_icon = pystray.Icon("ClipboardBridge", self.create_tray_image(), "Clipboard Bridge", menu)
        self.tray_icon.run()

    def toggle_startup_from_tray(self, icon, item):
        current = self.config.get('run_on_startup', False)
        new_state = not current
        self.config['run_on_startup'] = new_state
        self.set_startup(new_state)
        self.save_config()
        if hasattr(self, 'startup_switch'):
            self.root.after(0, lambda: self.startup_switch.select() if new_state else self.startup_switch.deselect())

    def quit_app(self, icon=None, item=None):
        if self.tray_icon:
            self.tray_icon.stop()
        self.stop_server()
        if self.root:
            try:
                self.root.after(0, self.root.destroy)
            except Exception:
                pass
        time.sleep(0.2)
        os._exit(0)

    # --- GUI Creation ---
    def build_gui(self):
        # Configure columns
        self.root.grid_columnconfigure(0, weight=0)
        self.root.grid_columnconfigure(1, weight=1)
        self.root.grid_rowconfigure(0, weight=1)
        
        # Sidebar Frame
        self.sidebar_frame = ctk.CTkFrame(self.root, width=180, corner_radius=0)
        self.sidebar_frame.grid(row=0, column=0, sticky="nsew")
        self.sidebar_frame.grid_rowconfigure(5, weight=1) # Push contents up
        
        logo = ctk.CTkLabel(self.sidebar_frame, text="Clipboard\nBridge", font=ctk.CTkFont(size=20, weight="bold"))
        logo.grid(row=0, column=0, padx=20, pady=(20, 30))
        
        # Sidebar buttons
        self.sb_buttons = {}
        sections = [("Dashboard", "dashboard"), ("Settings", "settings"), ("History", "history"), ("Logs", "logs")]
        for idx, (label, name) in enumerate(sections):
            btn = ctk.CTkButton(
                self.sidebar_frame, 
                text=label, 
                font=ctk.CTkFont(size=14, weight="normal"),
                height=35,
                anchor="w",
                fg_color="transparent",
                text_color="#A0AABF",
                hover_color="#2D3748",
                command=lambda n=name: self.select_frame(n)
            )
            btn.grid(row=idx+1, column=0, padx=10, pady=5, sticky="ew")
            self.sb_buttons[name] = btn
            
        # Main Area Content Frames
        self.frames = {
            "dashboard": ctk.CTkFrame(self.root, fg_color="transparent"),
            "settings": ctk.CTkFrame(self.root, fg_color="transparent"),
            "history": ctk.CTkFrame(self.root, fg_color="transparent"),
            "logs": ctk.CTkFrame(self.root, fg_color="transparent")
        }
        
        # Build individual frames
        self.build_dashboard_frame()
        self.build_settings_frame()
        self.build_history_frame()
        self.build_logs_frame()
        
        # Default select Dashboard
        self.select_frame("dashboard")

    def select_frame(self, name):
        # Update button highlights
        for k, btn in self.sb_buttons.items():
            if k == name:
                btn.configure(fg_color="#3182CE", text_color="#FFFFFF")
            else:
                btn.configure(fg_color="transparent", text_color="#A0AABF")
                
        # Hide all, show selected
        for k, frame in self.frames.items():
            frame.grid_forget()
            
        self.frames[name].grid(row=0, column=1, sticky="nsew", padx=25, pady=25)

    def build_dashboard_frame(self):
        frame = self.frames["dashboard"]
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(2, weight=1)
        
        # Title
        title_lbl = ctk.CTkLabel(frame, text="Dashboard", font=ctk.CTkFont(size=24, weight="bold"))
        title_lbl.grid(row=0, column=0, sticky="w", pady=(0, 15))
        
        # Status Card
        status_card = ctk.CTkFrame(frame, fg_color="#151E2E", corner_radius=12)
        status_card.grid(row=1, column=0, sticky="ew", pady=(0, 15), padx=2)
        
        # Canvas dot + Status Label
        status_inner = ctk.CTkFrame(status_card, fg_color="transparent")
        status_inner.pack(anchor="w", padx=20, pady=12)
        
        self.status_canvas = ctk.CTkCanvas(status_inner, width=16, height=16, bg="#151E2E", highlightthickness=0)
        self.status_canvas.pack(side=tk.LEFT, padx=(0, 10))
        self.status_dot = self.status_canvas.create_oval(2, 2, 14, 14, fill="#EF4444", outline="")
        
        self.status_label = ctk.CTkLabel(status_inner, text="Status: Initializing", font=ctk.CTkFont(size=15, weight="bold"))
        self.status_label.pack(side=tk.LEFT)
        
        local_ip = "Unknown"
        if self.server:
            local_ip = self.server.get_local_ip()
            
        self.ip_label = ctk.CTkLabel(status_card, text=f"IP Address: {local_ip}", font=ctk.CTkFont(size=13), text_color="#A0AABF")
        self.ip_label.pack(anchor="w", padx=20, pady=(0, 12))
        
        # QR Code Container Card
        qr_card = ctk.CTkFrame(frame, fg_color="#151E2E", corner_radius=12)
        qr_card.grid(row=2, column=0, sticky="nsew", pady=(0, 15), padx=2)
        
        qr_card.grid_columnconfigure(0, weight=1)
        qr_card.grid_rowconfigure(1, weight=1)
        
        info_lbl = ctk.CTkLabel(qr_card, text="Scan with Android client to pair and sync clipboards", font=ctk.CTkFont(size=12, slant="italic"), text_color="#A0AABF")
        info_lbl.grid(row=0, column=0, pady=(12, 5))
        
        self.qr_label = ctk.CTkLabel(qr_card, text="")
        self.qr_label.grid(row=1, column=0, sticky="nsew", pady=5)
        self.update_qr_code()
        
        # Pairing Key Card
        key_card = ctk.CTkFrame(frame, fg_color="transparent")
        key_card.grid(row=3, column=0, sticky="ew", pady=5)
        
        self.key_label = ctk.CTkLabel(
            key_card, 
            text=f"Key: {self.config['key']}", 
            font=ctk.CTkFont(family="Consolas", size=16, weight="bold"), 
            text_color="#4299E1"
        )
        self.key_label.pack(side=tk.LEFT, pady=5)
        
        copy_key_btn = ctk.CTkButton(
            key_card, 
            text="Copy", 
            width=60, 
            height=28,
            font=ctk.CTkFont(size=12),
            fg_color="#2D3748",
            hover_color="#4A5568",
            command=self.copy_key
        )
        copy_key_btn.pack(side=tk.LEFT, padx=15)
        self.copy_key_btn = copy_key_btn
        
        regen_btn = ctk.CTkButton(
            key_card, 
            text="Regenerate Key", 
            width=120, 
            height=28,
            font=ctk.CTkFont(size=12),
            fg_color="#EF4444",
            hover_color="#E53E3E",
            command=self.regenerate_key
        )
        regen_btn.pack(side=tk.RIGHT, pady=5)

    def update_qr_code(self):
        qr = qrcode.QRCode(box_size=4, border=2)
        qr.add_data(self.config['key'])
        qr.make(fit=True)
        # Standard black on white for 100% scannability and compatibility
        img = qr.make_image(fill_color="black", back_color="white")
        
        # Convert PIL image from binary to RGB mode for compatibility with CTkImage
        pil_img = img.convert("RGB")
        w, h = pil_img.size
        
        # Use CTkImage so CustomTkinter handles scaling cleanly on HighDPI screens
        self.qr_photo = ctk.CTkImage(light_image=pil_img, dark_image=pil_img, size=(w, h))
        if hasattr(self, 'qr_label'):
            self.qr_label.configure(image=self.qr_photo)

    def copy_key(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.config['key'])
        self.root.update()
        
        # Simple animation feedback
        self.copy_key_btn.configure(text="Copied!")
        self.root.after(1500, lambda: self.copy_key_btn.configure(text="Copy"))

    def regenerate_key(self):
        if messagebox.askyesno("Regenerate Key", "This will disconnect all devices. You will need to scan the new QR code on Android. Continue?"):
            self.config['key'] = self.generate_key()
            self.save_config()
            self.stop_server()
            self.start_server()
            
            # Update key UI
            self.key_label.configure(text=f"Key: {self.config['key']}")
            self.update_qr_code()
            self.log_callback("Pairing key regenerated.")

    def build_settings_frame(self):
        frame = self.frames["settings"]
        frame.grid_columnconfigure(0, weight=1)
        
        # Title
        title_lbl = ctk.CTkLabel(frame, text="Settings", font=ctk.CTkFont(size=24, weight="bold"))
        title_lbl.grid(row=0, column=0, sticky="w", pady=(0, 20))
        
        # Startup Card
        opt_card = ctk.CTkFrame(frame, fg_color="#151E2E", corner_radius=12)
        opt_card.grid(row=1, column=0, sticky="ew", pady=10, padx=2)
        
        self.startup_switch = ctk.CTkSwitch(
            opt_card, 
            text="Run on Windows Startup", 
            font=ctk.CTkFont(size=14),
            command=self.toggle_startup_from_switch
        )
        self.startup_switch.pack(anchor="w", padx=20, pady=16)
        if self.config.get('run_on_startup', False):
            self.startup_switch.select()
        else:
            self.startup_switch.deselect()
            
        # Sync Direction Card
        dir_card = ctk.CTkFrame(frame, fg_color="#151E2E", corner_radius=12)
        dir_card.grid(row=2, column=0, sticky="ew", pady=10, padx=2)
        
        dir_lbl = ctk.CTkLabel(dir_card, text="Sync Direction", font=ctk.CTkFont(size=15, weight="bold"))
        dir_lbl.pack(anchor="w", padx=20, pady=(15, 5))
        
        info_lbl = ctk.CTkLabel(
            dir_card, 
            text="Choose whether updates sync bidirectionally or uni-directionally.", 
            font=ctk.CTkFont(size=12),
            text_color="#94A3B8"
        )
        info_lbl.pack(anchor="w", padx=20, pady=(0, 15))
        
        self.direction_btn = ctk.CTkSegmentedButton(
            dir_card,
            values=["Bidirectional", "Windows -> Android", "Android -> Windows"],
            font=ctk.CTkFont(size=13),
            command=self.update_direction_segmented
        )
        self.direction_btn.pack(anchor="w", padx=20, pady=(0, 20), fill=tk.X)
        
        # Select current value
        current_val = self.config.get('sync_direction', 'bidirectional')
        rev_val_map = {
            "bidirectional": "Bidirectional",
            "windows_to_android": "Windows -> Android",
            "android_to_windows": "Android -> Windows"
        }
        self.direction_btn.set(rev_val_map.get(current_val, "Bidirectional"))

    def toggle_startup_from_switch(self):
        new_state = self.startup_switch.get() == 1
        self.config['run_on_startup'] = new_state
        self.set_startup(new_state)
        self.save_config()

    def update_direction_segmented(self, value):
        val_map = {
            "Bidirectional": "bidirectional",
            "Windows -> Android": "windows_to_android",
            "Android -> Windows": "android_to_windows"
        }
        self.config['sync_direction'] = val_map.get(value, "bidirectional")
        self.save_config()

    def build_history_frame(self):
        frame = self.frames["history"]
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)
        
        # Header layout
        header_frame = ctk.CTkFrame(frame, fg_color="transparent")
        header_frame.grid(row=0, column=0, sticky="ew", pady=(0, 15))
        
        title_lbl = ctk.CTkLabel(header_frame, text="Clipboard History", font=ctk.CTkFont(size=24, weight="bold"))
        title_lbl.pack(side=tk.LEFT)
        
        clear_btn = ctk.CTkButton(
            header_frame,
            text="Clear",
            width=70,
            height=28,
            fg_color="#EF4444",
            hover_color="#E53E3E",
            command=self.clear_history
        )
        clear_btn.pack(side=tk.RIGHT)
        
        # Scrollable Frame
        self.history_scrollable = ctk.CTkScrollableFrame(frame, fg_color="#151E2E", corner_radius=12)
        self.history_scrollable.grid(row=1, column=0, sticky="nsew", padx=2)

    def clear_history(self):
        if messagebox.askyesno("Clear History", "Do you want to clear the clipboard history log?"):
            self.history.clear()
            self._update_history_ui()

    def build_logs_frame(self):
        frame = self.frames["logs"]
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)
        
        # Header Layout
        header_frame = ctk.CTkFrame(frame, fg_color="transparent")
        header_frame.grid(row=0, column=0, sticky="ew", pady=(0, 15))
        
        title_lbl = ctk.CTkLabel(header_frame, text="System Logs", font=ctk.CTkFont(size=24, weight="bold"))
        title_lbl.pack(side=tk.LEFT)
        
        clear_logs_btn = ctk.CTkButton(
            header_frame,
            text="Clear",
            width=70,
            height=28,
            fg_color="#4A5568",
            hover_color="#718096",
            command=self.clear_logs
        )
        clear_logs_btn.pack(side=tk.RIGHT, padx=5)
        
        copy_logs_btn = ctk.CTkButton(
            header_frame,
            text="Copy All",
            width=80,
            height=28,
            fg_color="#2D3748",
            hover_color="#4A5568",
            command=self.copy_logs
        )
        copy_logs_btn.pack(side=tk.RIGHT, padx=5)
        self.copy_logs_btn = copy_logs_btn
        
        # Textbox
        self.log_text = ctk.CTkTextbox(
            frame, 
            fg_color="#0A0A0A", 
            text_color="#00FF66", 
            font=ctk.CTkFont(family="Consolas", size=12),
            corner_radius=12
        )
        self.log_text.grid(row=1, column=0, sticky="nsew", padx=2)
        self.log_text.configure(state=tk.DISABLED)

    def clear_logs(self):
        if hasattr(self, 'log_text'):
            self.log_text.configure(state=tk.NORMAL)
            self.log_text.delete("1.0", tk.END)
            self.log_text.configure(state=tk.DISABLED)

    def copy_logs(self):
        if hasattr(self, 'log_text'):
            logs_content = self.log_text.get("1.0", tk.END).strip()
            if logs_content:
                self.root.clipboard_clear()
                self.root.clipboard_append(logs_content)
                self.root.update()
                
                # Feedback
                self.copy_logs_btn.configure(text="Copied!")
                self.root.after(1500, lambda: self.copy_logs_btn.configure(text="Copy All"))

    # --- Window Controls ---
    def show_window(self, icon=None, item=None):
        if self.root:
            self.root.after(0, self._ui_show_window)

    def _ui_show_window(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def hide_window(self):
        if self.root:
            self.root.after(0, self.root.withdraw)

if __name__ == "__main__":
    app = ClipboardBridgeApp()
