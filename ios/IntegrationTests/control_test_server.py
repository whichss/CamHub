import base64
import json
import socket
import ssl
import sys

certificate, key, port_file, scenario = sys.argv[1:]
listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
listener.bind(("127.0.0.1", 0))
listener.listen(1)
listener.settimeout(10)
with open(port_file, "w", encoding="utf-8") as handle:
    handle.write(str(listener.getsockname()[1]))
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(certificate, key)
client, _ = listener.accept()
try:
    tls = context.wrap_socket(client, server_side=True)
except ssl.SSLError:
    listener.close()
    if scenario != "reject-certificate":
        raise
    sys.exit(0)
with tls:
    tls.settimeout(10)
    stream = tls.makefile("rb")
    message = json.loads(stream.readline())
    if message.get("type") != "hello":
        raise RuntimeError("Unexpected hello")
    if message.get("pin") != "12345678":
        if scenario != "wrong-pin":
            raise RuntimeError("Unexpected PIN")
        response = {"type": "error", "stringValue": "invalid_pairing_pin"}
    else:
        if scenario != "success":
            raise RuntimeError("Unexpected successful authentication")
        response = {"type": "hello_ack", "deviceName": "Test Camera", "udpStreamPort": 12345,
                    "sessionKey": base64.b64encode(bytes(32)).decode("ascii")}
    tls.sendall(json.dumps(response).encode("utf-8") + b"\n")
    # A successful authenticated shutdown must unsubscribe before closing the control channel.
    last_line = stream.readline()
    if scenario == "success":
        last_message = json.loads(last_line)
        if last_message.get("type") != "command" or last_message.get("command") != "udp_unsubscribe":
            raise RuntimeError("Expected UDP unsubscribe before disconnect")
listener.close()
