package com.camhub.studio.ui.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.camhub.studio.data.network.PeerTrustRequest

@Composable
fun PeerTrustDialog(
    request: PeerTrustRequest?,
    onResolve: (requestId: Long, trust: Boolean, pairingPin: String) -> Unit
) {
    if (request == null) return
    var pairingPin by remember(request.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onResolve(request.id, false, "") },
        title = { Text("Verify camera identity") },
        text = {
            Column {
                Text("Compare the SHA-256 prefix below with VERIFY on ${request.peer.name}.")
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        request.fingerprint.take(16).chunked(4).joinToString(" "),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Reveal the camera's PAIR PIN on its screen and enter it here. The camera will not release its video key until the PIN matches.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pairingPin,
                    onValueChange = { value ->
                        if (value.length <= 8 && value.all { it in '0'..'9' }) pairingPin = value
                    },
                    label = { Text("Camera pairing PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pairingPin.length == 8,
                onClick = { onResolve(request.id, true, pairingPin) }
            ) {
                Text("VERIFY & CONNECT")
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(request.id, false, "") }) {
                Text("CANCEL")
            }
        }
    )
}
