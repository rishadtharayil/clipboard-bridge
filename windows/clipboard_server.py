import socket
import threading
import time
import hashlib
import io
import os
import struct
import win32gui
import win32con
import win32clipboard
import ctypes
from PIL import Image, ImageGrab
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import win11toast

class ClipboardServer:
    def __init__(self, key, config, log_callback, status_callback, history_callback=None):
        self.key = key
        self.config = config
        self.log_callback = log_callback
        self.status_callback = status_callback
        self.history_callback = history_callback
        
        # Derive AES key
        salt = b"ClipboardBridgeSalt"
        md = hashlib.sha256()
        md.update(key.encode('utf-8') + salt)
        self.aes_key = md.digest()

        self.tcp_port = 9090
        self.udp_port = 9091
        self.is_running = False
        
        # Connection tracking
        self.clients = {}  # socket: Lock
        self.clients_lock = threading.Lock()
        
        # Feedback prevention hashes
        self.last_sent_text_hash = None
        self.last_sent_image_hash = None
        self.last_recv_text_hash = None
        self.last_recv_image_hash = None
        
        # Network sockets
        self.tcp_socket = None
        self.udp_socket = None
        self.listener_thread_id = None
        self.hwnd = None
        self.ignore_next_clipboard_change = False

    def log(self, message):
        self.log_callback(message)

    def update_status(self):
        with self.clients_lock:
            count = len(self.clients)
        status = f"Running | {count} Client(s) Connected" if self.is_running else "Stopped"
        self.status_callback(status)

    def encrypt(self, data: bytes) -> bytes:
        aesgcm = AESGCM(self.aes_key)
        iv = os.urandom(12)
        ciphertext = aesgcm.encrypt(iv, data, None)
        return iv + ciphertext

    def decrypt(self, payload: bytes) -> bytes:
        if len(payload) < 12:
            raise ValueError("Encrypted payload too short")
        aesgcm = AESGCM(self.aes_key)
        iv = payload[:12]
        ciphertext = payload[12:]
        return aesgcm.decrypt(iv, ciphertext, None)

    def start(self):
        self.is_running = True
        self.update_status()

        # Start TCP server
        threading.Thread(target=self.tcp_server_loop, daemon=True).start()
        # Start UDP broadcaster
        threading.Thread(target=self.udp_broadcast_loop, daemon=True).start()
        # Start Clipboard Listener window
        threading.Thread(target=self.clipboard_listener_loop, daemon=True).start()
        
        self.log(f"Server started. Pairing Key: {self.key}")

    def stop(self):
        self.is_running = False
        
        # Close sockets
        if self.tcp_socket:
            try:
                self.tcp_socket.close()
            except:
                pass
            self.tcp_socket = None

        if self.udp_socket:
            try:
                self.udp_socket.close()
            except:
                pass
            self.udp_socket = None

        # Disconnect all clients
        with self.clients_lock:
            for sock in list(self.clients.keys()):
                try:
                    sock.close()
                except:
                    pass
            self.clients.clear()

        # Stop Clipboard Listener hidden window
        if self.hwnd:
            try:
                ctypes.windll.user32.RemoveClipboardFormatListener(self.hwnd)
                win32gui.PostMessage(self.hwnd, win32con.WM_CLOSE, 0, 0)
            except Exception as e:
                self.log(f"Error stopping listener: {e}")
            self.hwnd = None

        self.update_status()
        self.log("Server stopped.")

    def get_local_ip(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(('10.255.255.255', 1))
            ip = s.getsockname()[0]
        except Exception:
            ip = '127.0.0.1'
        finally:
            s.close()
        return ip

    def udp_broadcast_loop(self):
        self.log(f"UDP broadcaster active on port {self.udp_port}...")
        while self.is_running:
            try:
                hostname = socket.gethostname()
                _, _, ips = socket.gethostbyname_ex(hostname)
                
                for ip in ips:
                    if ip.startswith('127.'):
                        continue
                    try:
                        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                        sock.bind((ip, 0))
                        message = f"CLIPBOARD_BRIDGE_SERVER:{ip}:{self.tcp_port}"
                        
                        # Send to limited broadcast
                        sock.sendto(message.encode('utf-8'), ('255.255.255.255', self.udp_port))
                        
                        # Send to subnet broadcast (class C fallback)
                        parts = ip.split('.')
                        if len(parts) == 4:
                            subnet_broadcast = f"{parts[0]}.{parts[1]}.{parts[2]}.255"
                            sock.sendto(message.encode('utf-8'), (subnet_broadcast, self.udp_port))
                        
                        sock.close()
                    except Exception:
                        pass
            except Exception as e:
                if self.is_running:
                    self.log(f"UDP broadcast error: {e}")
            time.sleep(2.0)

    def tcp_server_loop(self):
        self.tcp_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.tcp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            self.tcp_socket.bind(('0.0.0.0', self.tcp_port))
            self.tcp_socket.listen(5)
            self.log(f"TCP server listening on port {self.tcp_port}...")
            
            while self.is_running:
                client_sock, addr = self.tcp_socket.accept()
                self.log(f"Android client connected from {addr[0]}:{addr[1]}")
                
                with self.clients_lock:
                    self.clients[client_sock] = threading.Lock()
                self.update_status()
                
                threading.Thread(target=self.client_reader_loop, args=(client_sock, addr), daemon=True).start()
        except Exception as e:
            if self.is_running:
                self.log(f"TCP server error: {e}")

    def client_reader_loop(self, sock, addr):
        try:
            while self.is_running:
                # Read header (8 bytes)
                header = sock.recv(8)
                if not header or len(header) < 8:
                    break
                
                msg_type, length = struct.unpack('!II', header)
                if length < 0 or length > 50 * 1024 * 1024:
                    raise ValueError(f"Packet size too large: {length}")
                
                # Read encrypted payload
                payload = b''
                while len(payload) < length:
                    chunk = sock.recv(length - len(payload))
                    if not chunk:
                        break
                    payload += chunk
                
                if len(payload) < length:
                    break

                try:
                    decrypted = self.decrypt(payload)
                    
                    # Check sync direction
                    sync_dir = self.config.get('sync_direction', 'bidirectional')
                    if sync_dir == 'windows_to_android':
                        self.log("Ignored packet from Android (Sync direction is Windows -> Android only)")
                        continue

                    if msg_type == 0:  # Text
                        text = decrypted.decode('utf-8')
                        self.log("Received text from Android client")
                        self.write_text_to_clipboard(text)
                        if self.history_callback: self.history_callback(f"Text: {text[:50]}...")
                        win11toast.toast("Clipboard Bridge", "Text received from Android")
                    elif msg_type == 1:  # Image
                        self.log(f"Received image from Android client ({len(decrypted)} bytes)")
                        self.write_image_to_clipboard(decrypted)
                        if self.history_callback: self.history_callback("Image received")
                        win11toast.toast("Clipboard Bridge", "Image received from Android")
                    elif msg_type == 2:  # Heartbeat
                        # Respond back with heartbeat
                        self.send_packet_to_client(sock, self.clients[sock], 2, b'')
                except Exception as e:
                    self.log(f"Failed to process client packet: {e}")
        except Exception as e:
            if self.is_running:
                self.log(f"Client read error ({addr[0]}): {e}")
        finally:
            sock.close()
            with self.clients_lock:
                if sock in self.clients:
                    del self.clients[sock]
            self.update_status()
            self.log(f"Android client disconnected: {addr[0]}")

    def send_to_all(self, msg_type: int, payload: bytes):
        with self.clients_lock:
            active_clients = list(self.clients.items())
        
        for sock, lock in active_clients:
            self.send_packet_to_client(sock, lock, msg_type, payload)

    def send_packet_to_client(self, sock, lock, msg_type: int, payload: bytes):
        try:
            with lock:
                encrypted = self.encrypt(payload)
                header = struct.pack('!II', msg_type, len(encrypted))
                sock.sendall(header + encrypted)
        except Exception as e:
            sock.close()
            with self.clients_lock:
                if sock in self.clients:
                    del self.clients[sock]
            self.update_status()

    # --- Clipboard operations ---

    def hash_data(self, data: bytes) -> str:
        return hashlib.sha256(data).hexdigest()

    def handle_clipboard_change(self):
        # We need to run this on a separate thread or safely to avoid blocking the Win32 message pump
        if getattr(self, 'ignore_next_clipboard_change', False):
            self.ignore_next_clipboard_change = False
            return
        threading.Thread(target=self._process_clipboard_change, daemon=True).start()

    def _process_clipboard_change(self):
        # We process this with a tiny delay to ensure the clipboard is ready to read
        time.sleep(0.1)
        
        # Check sync direction
        sync_dir = self.config.get('sync_direction', 'bidirectional')
        if sync_dir == 'android_to_windows':
            return
            
        try:
            # 1. Try Image First
            img_bytes = self.read_image_from_clipboard()
            if img_bytes:
                h = self.hash_data(img_bytes)
                if h != self.last_recv_image_hash and h != self.last_sent_image_hash:
                    self.last_sent_image_hash = h
                    self.log("Local Windows image copy detected, syncing...")
                    self.send_to_all(1, img_bytes)
                return

            # 2. Check for text
            text = self.read_text_from_clipboard()
            if text:
                text_bytes = text.encode('utf-8')
                h = self.hash_data(text_bytes)
                if h != self.last_recv_text_hash and h != self.last_sent_text_hash:
                    self.last_sent_text_hash = h
                    self.log("Local Windows text copy detected, syncing...")
                    self.send_to_all(0, text_bytes)
                return
        except Exception as e:
            self.log(f"Error handling clipboard update: {e}")

    def read_text_from_clipboard(self) -> str:
        try:
            win32clipboard.OpenClipboard()
            if win32clipboard.IsClipboardFormatAvailable(win32clipboard.CF_UNICODETEXT):
                data = win32clipboard.GetClipboardData(win32clipboard.CF_UNICODETEXT)
                return data
        except Exception:
            pass
        finally:
            try:
                win32clipboard.CloseClipboard()
            except:
                pass
        return None

    def write_text_to_clipboard(self, text: str):
        h = self.hash_data(text.encode('utf-8'))
        self.last_recv_text_hash = h
        try:
            self.ignore_next_clipboard_change = True
            win32clipboard.OpenClipboard()
            win32clipboard.EmptyClipboard()
            win32clipboard.SetClipboardText(text, win32clipboard.CF_UNICODETEXT)
            self.log("Successfully wrote text to Windows clipboard")
        except Exception as e:
            self.ignore_next_clipboard_change = False
            self.log(f"Failed to write text to Windows clipboard: {e}")
        finally:
            try:
                win32clipboard.CloseClipboard()
            except:
                pass

    def read_image_from_clipboard(self) -> bytes:
        # 1. Check if an image file was copied from File Explorer (CF_HDROP)
        try:
            win32clipboard.OpenClipboard()
            if win32clipboard.IsClipboardFormatAvailable(win32clipboard.CF_HDROP):
                files = win32clipboard.GetClipboardData(win32clipboard.CF_HDROP)
                if files and len(files) > 0:
                    file_path = files[0]
                    ext = os.path.splitext(file_path)[1].lower()
                    if ext in ['.png', '.jpg', '.jpeg', '.bmp', '.gif', '.webp']:
                        win32clipboard.CloseClipboard()
                        with Image.open(file_path) as img:
                            bio = io.BytesIO()
                            img.save(bio, format='PNG')
                            return bio.getvalue()
        except Exception:
            pass
        finally:
            try:
                win32clipboard.CloseClipboard()
            except:
                pass

        # 2. Fallback to direct bitmap data (screenshots, snips, browser copy)
        try:
            img = ImageGrab.grabclipboard()
            if img is not None and isinstance(img, Image.Image):
                bio = io.BytesIO()
                img.save(bio, format='PNG')
                return bio.getvalue()
        except Exception:
            pass
        return None

    def write_image_to_clipboard(self, image_bytes: bytes):
        h = self.hash_data(image_bytes)
        self.last_recv_image_hash = h
        try:
            img = Image.open(io.BytesIO(image_bytes))
            
            # Prepare CF_DIB format (BMP without file header)
            output = io.BytesIO()
            img.convert("RGB").save(output, "BMP")
            dib_data = output.getvalue()[14:]  # Remove the 14-byte BITMAPFILEHEADER
            
            # Prepare PNG format
            png_output = io.BytesIO()
            img.save(png_output, "PNG")
            png_data = png_output.getvalue()
            
            self.ignore_next_clipboard_change = True
            win32clipboard.OpenClipboard()
            win32clipboard.EmptyClipboard()
            
            # Write CF_DIB
            win32clipboard.SetClipboardData(win32clipboard.CF_DIB, dib_data)
            
            # Write PNG
            png_format = win32clipboard.RegisterClipboardFormat("PNG")
            win32clipboard.SetClipboardData(png_format, png_data)
            
            self.log("Successfully wrote image to Windows clipboard (CF_DIB & PNG)")
        except Exception as e:
            self.ignore_next_clipboard_change = False
            self.log(f"Failed to write image to Windows clipboard: {e}")
        finally:
            try:
                win32clipboard.CloseClipboard()
            except:
                pass

    # --- Win32 Message Loop ---

    def clipboard_listener_loop(self):
        # Register window class
        wc = win32gui.WNDCLASS()
        wc.lpfnWndProc = self.wnd_proc
        wc.lpszClassName = "ClipboardBridgeServerListener"
        wc.hInstance = win32gui.GetModuleHandle(None)
        
        try:
            class_atom = win32gui.RegisterClass(wc)
        except Exception:
            # Already registered or failed
            class_atom = "ClipboardBridgeServerListener"

        # Create hidden Message-Only window
        self.hwnd = win32gui.CreateWindow(
            class_atom,
            "ClipboardBridgeServerListenerWindow",
            0, 0, 0, 0, 0,
            win32con.HWND_MESSAGE,
            0,
            wc.hInstance,
            None
        )

        # Register for clipboard updates
        ctypes.windll.user32.AddClipboardFormatListener(self.hwnd)
        self.log("Windows clipboard listener active...")
        
        # Run message loop
        win32gui.PumpMessages()

    def wnd_proc(self, hwnd, msg, wparam, lparam):
        if msg == 0x031D:  # WM_CLIPBOARDUPDATE
            if self.is_running:
                self.handle_clipboard_change()
            return 0
        elif msg == win32con.WM_DESTROY:
            win32gui.PostQuitMessage(0)
            return 0
        return win32gui.DefWindowProc(hwnd, msg, wparam, lparam)
