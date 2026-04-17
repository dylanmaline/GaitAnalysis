import asyncio
import sys
import csv
import os
import json
import math

sys.stdout = open(sys.stdout.fileno(), mode='w', encoding='utf-8', buffering=1)
sys.stderr = open(sys.stderr.fileno(), mode='w', encoding='utf-8', buffering=1)
from bleak import BleakScanner, BleakClient, BleakError

UART_SERVICE_UUID = "49535343-fe7d-4ae5-8fa9-9fafd205e455"
TX_CHAR_UUID      = "49535343-1e4d-4bd9-ba61-23c647249616"
RX_CHAR_UUID      = "49535343-8841-43f4-a8d4-ecbe34729bb3"

DEVICE_NAME       = "RNBD350"
SCAN_TIMEOUT      = 10.0
CONNECT_TIMEOUT   = 5.0
RETRY_DELAY       = 3.0

OUTPUT_FILE   = "data.csv"          
TARGET_HZ     = 5                   
PERIOD        = 1.0 / TARGET_HZ     

CSV_HEADER    = ["ax", "ay", "az", "fsr1", "fsr2", "fsr3", "fsr4", "fsr5"]

SAVE_INTERVAL = 25                  
CALIB_FILE    = "calibration.json"  

receive_buffer  = ""
all_rows        = []
row_count       = 0

mode = "idle"


calib_samples      = []
CALIB_SAMPLE_COUNT = 25             


theta1 = None
theta2 = None
theta3 = None
signx  = None


ax_avg = None
ay_avg = None
az_avg = None


last_row_time = 0.0     


SIGNAL_STATIC  = "done_static.txt"
SIGNAL_RIGHT   = "done_right.txt"
SIGNAL_FORWARD = "done_forward.txt"


def compute_calibration(static_avg, right_avg, forward_avg):
   
    global theta1, theta2, theta3, signx
    global ax_avg, ay_avg, az_avg 

    ax1, ay1, az1 = static_avg
    ax2, ay2, az2 = right_avg
    ax3, ay3, az3 = forward_avg

    
    ax_avg, ay_avg, az_avg = ax1, ay1, az1

    
    theta1 = math.atan2(ay1, ax1)

    
    theta2 = math.atan2(math.sqrt(ax1**2 + ay1**2), az1)

    
    ax2_1 = ax2 * math.cos(-theta1) - ay2 * math.sin(-theta1)
    ay2_1 = ax2 * math.sin(-theta1) + ay2 * math.cos(-theta1)
    az2_1 = az2

    ax2_2 = ax2_1 * math.cos(-theta2) + az2_1 * math.sin(-theta2)
    ay2_2 = ay2_1

    
    theta3 = math.atan2(ax2_2, ay2_2)

    
    ax3_1 = ax3 * math.cos(-theta1) - ay3 * math.sin(-theta1)
    ay3_1 = ax3 * math.sin(-theta1) + ay3 * math.cos(-theta1)
    az3_1 = az3

    ax3_2 = ax3_1 * math.cos(-theta2) + az3_1 * math.sin(-theta2)
    ay3_2 = ay3_1
    az3_2 = -ax3_1 * math.sin(-theta2) + az3_1 * math.cos(-theta2)

    ax3_f = ax3_2 * math.cos(theta3) - ay3_2 * math.sin(theta3)

    signx = 1 if ax3_f >= 0 else -1

    
    calib = {
        "theta1": theta1, "theta2": theta2, "theta3": theta3, "signx": signx,
        "ax_avg": ax_avg, "ay_avg": ay_avg, "az_avg": az_avg,
    }
    with open(CALIB_FILE, "w") as f:
        json.dump(calib, f, indent=2)

    print(f"[CALIB] theta1={theta1:.4f} theta2={theta2:.4f} "
          f"theta3={theta3:.4f} signx={signx}  ->  saved to {CALIB_FILE}")
    print(f"[CALIB] gravity offsets  ax={ax_avg:.4f} ay={ay_avg:.4f} az={az_avg:.4f}")


def load_calibration():
    global theta1, theta2, theta3, signx
    global ax_avg, ay_avg, az_avg  
    try:
        with open(CALIB_FILE) as f:
            c = json.load(f)
        theta1 = c["theta1"]
        theta2 = c["theta2"]
        theta3 = c["theta3"]
        signx  = c["signx"]
        if "ax_avg" in c and "ay_avg" in c and "az_avg" in c:
            ax_avg = c["ax_avg"]
            ay_avg = c["ay_avg"]
            az_avg = c["az_avg"]
        else:
            ax_avg = 0.0
            ay_avg = 0.0
            az_avg = 0.0
            print("[CALIB] WARNING: calibration.json has no gravity offsets. "
                  "Data will NOT have gravity removed. Re-run calibration to fix.")
        print(f"[CALIB] Loaded calibration from {CALIB_FILE}")
        return True
    except Exception:
        return False


def transform(ax, ay, az):
    
    # Tilt correction about Z
    x1 = ax * math.cos(-theta1) - ay * math.sin(-theta1)
    y1 = ax * math.sin(-theta1) + ay * math.cos(-theta1)
    z1 = az

    # Tilt correction about Y
    x2 =  x1 * math.cos(-theta2) + z1 * math.sin(-theta2)
    y2 =  y1
    z2 = -x1 * math.sin(-theta2) + z1 * math.cos(-theta2)

    # Horizontal alignment
    x3 =  x2 * math.cos(theta3) - y2 * math.sin(theta3)
    y3 =  x2 * math.sin(theta3) + y2 * math.cos(theta3)
    z3 =  z2

    return (x3 * signx, y3, z3)

def save_csv():
    
    with open(OUTPUT_FILE, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(CSV_HEADER)
        writer.writerows(all_rows)
    print(f"  -> Saved {len(all_rows)} rows to {OUTPUT_FILE}")


def write_signal(filename):
    with open(filename, "w") as f:
        f.write("done")



def on_data_received(sender, data: bytearray):
   
    global receive_buffer, row_count, mode, calib_samples
    global last_row_time, theta1

    receive_buffer += data.decode("utf-8", errors="replace")

    while "\n" in receive_buffer:
        line, receive_buffer = receive_buffer.split("\n", 1)
        line = line.strip()

        if not line:
            continue

        if any(line.startswith(x) for x in
               ["ERROR", "WARNING", "Init", "BMA", "Check", "OK", "Ax", "FSR", "  "]):
            print(f"[DIAG] {line}")
            continue

        if mode == "idle":
            continue

        # Parse the 8-column row: ax,ay,az,fsr1,fsr2,fsr3,fsr4,fsr5
        parts = line.split(",")
        if len(parts) != 8:
            print(f"[SKIP] {line}")
            continue

        try:
            vals = [float(p) for p in parts]
        except ValueError:
            print(f"[SKIP] {line}")
            continue

        ax_raw, ay_raw, az_raw = vals[0], vals[1], vals[2]
        fsrs = vals[3:]   

        if mode in ("calib_static", "calib_right", "calib_forward"):
            calib_samples.append((ax_raw, ay_raw, az_raw))
            print(f"[CALIB {mode}] {len(calib_samples)}/{CALIB_SAMPLE_COUNT}")
            return  # accumulation handled by the async task

        if mode != "collect":
            continue

        import time as _time
        now = _time.monotonic()
        if (now - last_row_time) < PERIOD:
            continue
        last_row_time = now

        ax_c = ax_raw - ax_avg
        ay_c = ay_raw - ay_avg
        az_c = az_raw - az_avg

        ax_t, ay_t, az_t = transform(ax_c, ay_c, az_c)

        row = [round(ax_t, 4), round(ay_t, 4), round(az_t, 4)] + [int(v) for v in fsrs]
        all_rows.append(row)
        row_count += 1
        print(f"[ROW {row_count:05d}] {row}")

        if row_count % SAVE_INTERVAL == 0:
            print(f"\n[CHECKPOINT] {row_count} rows - saving...")
            save_csv()
            print()


async def run_calibration():
    
    global mode, calib_samples

    averages = []

    for phase, signal_file, prompt in [
        ("calib_static",  SIGNAL_STATIC,  "resting"),
        ("calib_right",   SIGNAL_RIGHT,   "move RIGHT"),
        ("calib_forward", SIGNAL_FORWARD, "move FORWARD"),
    ]:
        calib_samples = []
        mode = phase
        print(f"\n[CALIB] Phase: {prompt}  - collecting {CALIB_SAMPLE_COUNT} samples...")

        while len(calib_samples) < CALIB_SAMPLE_COUNT:
            await asyncio.sleep(0.05)

        n = len(calib_samples)
        avg = (
            sum(s[0] for s in calib_samples) / n,
            sum(s[1] for s in calib_samples) / n,
            sum(s[2] for s in calib_samples) / n,
        )
        averages.append(avg)
        print(f"[CALIB] {phase} avg: ax={avg[0]:.3f} ay={avg[1]:.3f} az={avg[2]:.3f}")

        write_signal(signal_file)   # unblock Java waitForPythonSignal()

    compute_calibration(*averages)
    mode = "idle"
    print("[CALIB] All phases complete. Ready to collect data.")




async def input_listener(stop_event):
    
    global mode, row_count, last_row_time

    loop = asyncio.get_running_loop()

    while not stop_event.is_set():
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            break
        cmd = line.strip().lower()
        if not cmd:
            continue

        if cmd in ('c', 'calibrate'):
            if mode != "idle":
                print("[INFO] Finish current operation first.")
            else:
                asyncio.create_task(run_calibration())

        elif cmd in ('s', 'start'):
            if theta1 is None and not load_calibration():
                print("[ERROR] No calibration found. Run calibration first (calibrate first).")
            elif mode != "idle":
                print("[INFO] Already running.")
            else:
                all_rows.clear()
                row_count = 0
                last_row_time = 0.0
                mode = "collect"
                print("\n[LOGGING STARTED] Data is now being recorded...\n")

        elif cmd in ('p', 'pause'):
            if mode == "collect":
                print("\n[LOGGING PAUSED] Saving data.csv...")
                mode = "idle"
                if all_rows:
                    save_csv()
                else:
                    print("[INFO] No data collected - CSV not written.")
            else:
                print("[INFO] Not currently collecting; nothing to pause.")

        elif cmd in ('q', 'quit', 'exit', 'stop'):
            print("\nStopping and saving data...")
            mode = "idle"
            if all_rows:
                save_csv()
            else:
                print("[INFO] No data collected - CSV not written.")
            stop_event.set()
            break

        else:
            print(f"[INFO] Unknown command: {cmd!r}")


async def find_device():
    print(f"Scanning for '{DEVICE_NAME}'...")
    device = await BleakScanner.find_device_by_filter(
        lambda d, _: d.name and DEVICE_NAME.lower() in d.name.lower(),
        timeout=SCAN_TIMEOUT
    )
    if device:
        print(f"Found: {device.name}  [{device.address}]")
    else:
        print(f"Device not found within {SCAN_TIMEOUT}s.")
    return device


async def run_session(address, stop_event):
    disconnected_event = asyncio.Event()

    def on_disconnect(client):
        print("\n[DISCONNECTED] Device went out of range or powered off.")
        if all_rows:
            print("[AUTO-SAVE] Saving current data...")
            save_csv()
        disconnected_event.set()

    try:
        async with BleakClient(
            address,
            disconnected_callback=on_disconnect,
            timeout=CONNECT_TIMEOUT
        ) as client:
            print("Connected!\n")
            await client.start_notify(TX_CHAR_UUID, on_data_received)

            while not stop_event.is_set() and not disconnected_event.is_set():
                await asyncio.sleep(0.1)

            try:
                await client.stop_notify(TX_CHAR_UUID)
            except Exception:
                pass

    except (BleakError, OSError) as e:
        print(f"[CONNECTION ERROR] {e}")

    return stop_event.is_set()



async def main():
    stop_event = asyncio.Event()

    
    if load_calibration():
        print("[INFO] Existing calibration loaded. Press 's' to start collecting "
              "or 'c' to re-calibrate.")
    else:
        print("[INFO] No calibration found. Press 'c' to calibrate before collecting.")

    device = await find_device()
    if device is None:
        print("Tips:")
        print("  • Blue LED should be blinking once every 3 seconds")
        print("  • Remove any existing Windows Bluetooth pairing first")
        sys.exit(1)

    address = device.address
    print(f"\nConnecting to {address}...")

    listener_task = asyncio.create_task(input_listener(stop_event))

    try:
        while not stop_event.is_set():
            intentional_stop = await run_session(address, stop_event)
            if intentional_stop:
                break
            print(f"\n[RECONNECTING] Retrying in {RETRY_DELAY}s...\n")
            await asyncio.sleep(RETRY_DELAY)

    except KeyboardInterrupt:
        stop_event.set()

    finally:
        listener_task.cancel()
        try:
            await listener_task
        except asyncio.CancelledError:
            pass

        if all_rows:
            print(f"\n[FINAL SAVE] Writing {len(all_rows)} total rows...")
            save_csv()
        else:
            print("\n[INFO] No data collected - CSV not created.")

        print(f"\nDone. Total rows logged: {row_count}")


if __name__ == "__main__":
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nStopped.")
