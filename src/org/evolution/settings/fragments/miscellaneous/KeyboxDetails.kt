/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.util.Base64
import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

/** Offline keybox details, ported from Duck Detector's RKP and StrongBox probes. */
internal object KeyboxDetails {

    private const val PROVISIONING_INFO_OID = "1.3.6.1.4.1.11129.2.1.30"
    private const val OID_CN = "2.5.4.3"
    private const val OID_SERIAL = "2.5.4.5"
    private const val OID_C = "2.5.4.6"
    private const val OID_O = "2.5.4.10"
    private const val OID_OU = "2.5.4.11"
    private const val OID_TITLE = "2.5.4.12"

    private val ATTRIBUTE_NAMES = mapOf(
        OID_CN to "CN", OID_SERIAL to "serialNumber", OID_C to "C", OID_O to "O",
        OID_OU to "OU", OID_TITLE to "title", "2.5.4.7" to "L", "2.5.4.8" to "ST",
    )

    private val OEM_MARKERS = linkedMapOf(
        "SAMSUNG" to "Samsung", "XIAOMI" to "Xiaomi", "REDMI" to "Xiaomi",
        "ONEPLUS" to "OnePlus", "OPPO" to "OPPO", "REALME" to "realme", "VIVO" to "vivo",
        "HUAWEI" to "Huawei", "HONOR" to "Honor", "MOTOROLA" to "Motorola",
        "LENOVO" to "Lenovo", "SONY" to "Sony", "LGELECTRONICS" to "LG", "LGE" to "LG",
        "NOKIA" to "Nokia", "HMDGLOBAL" to "HMD Global", "ASUS" to "ASUS",
        "NOTHING" to "Nothing", "FAIRPHONE" to "Fairphone", "ZTE" to "ZTE", "NUBIA" to "nubia",
        "TCL" to "TCL", "SHARP" to "Sharp", "KYOCERA" to "Kyocera", "MEIZU" to "Meizu",
        "TECNO" to "TECNO", "INFINIX" to "Infinix", "TRANSSION" to "Transsion",
        "AMAZON" to "Amazon", "NVIDIA" to "NVIDIA", "BLACKSHARK" to "Black Shark",
        "GOOGLE" to "Google",
    )

    private val TEE_VENDOR_MARKERS = linkedMapOf(
        "QUALCOMM" to "Qualcomm", "QTI" to "Qualcomm", "MEDIATEK" to "MediaTek",
        "TRUSTONIC" to "Trustonic", "KINIBI" to "Trustonic", "UNISOC" to "Unisoc",
        "SPREADTRUM" to "Unisoc", "EXYNOS" to "Samsung (Exynos)", "TEEGRIS" to "Samsung (TEEGRIS)",
        "HISILICON" to "HiSilicon", "BEANPOD" to "Beanpod", "MICROTRUST" to "Microtrust",
        "NXP" to "NXP", "THALES" to "Thales", "GIESECKE" to "G+D", "INFINEON" to "Infineon",
    )

    /** Google hardware attestation roots by SHA-256 of their DER, with a display label. */
    private val GOOGLE_ROOT_LABELS = mapOf(
        "1EF1A04B8BA58AB94589AC498C8982A783F24EA7307E0159A0C3A73B377D87CC" to "Google RSA-4096 (2019)",
        "CEDB1CB6DC896AE5EC797348BCE9286753C2B38EE71CE0FBE34A9A1248800DFC" to "Google RSA-4096 (2022 reissue)",
        "6D9DB4CE6C5C0B293166D08986E05774A8776CEB525D9E4329520DE12BA4BCC0" to "Google EC P-384 Key Attestation CA1 (2025)",
    )

    fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))

    private fun sha256Hex(input: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02X".format(it) }

    private class ParsedKey(val algorithm: String, val certPems: List<String>)

    /**
     * Every complete key as (normalised algorithm, parsed chain leaf first). Keys whose
     * certificates do not all parse keep the ones that did. Empty when the XML is unreadable.
     */
    private fun chains(xml: String): List<Pair<String, List<X509Certificate>>> = try {
        parseKeys(sanitize(xml)).map { key ->
            normaliseAlgorithm(key.algorithm) to key.certPems.mapNotNull { parseCertificate(it) }
        }.filter { it.second.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    /** KeyBoxManager.sanitizeXml(): trim, strip a leading BOM and XML comments. */
    private fun sanitize(content: String): String {
        var s = content.trim()
        for (bom in listOf("\uFEFF", "\uFFFE", "\u0000\uFEFF")) {
            if (s.startsWith(bom)) s = s.substring(bom.length)
        }
        return s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").trim()
    }

    /**
     * Complete keys only (algorithm, private key and at least one certificate), as counted by
     * KeyBoxManager. Throws when the document is cut off before its root element closes;
     * trailing garbage after a closed root is tolerated like the framework does.
     */
    private fun parseKeys(xml: String): List<ParsedKey> {
        val keys = mutableListOf<ParsedKey>()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var depth = 0
        var rootClosed = false
        var inKey = false
        var algorithm: String? = null
        var hasPrivateKey = false
        val certs = mutableListOf<String>()
        var text: StringBuilder? = null
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        when (parser.name) {
                            "Key" -> {
                                inKey = true
                                algorithm = parser.getAttributeValue(null, "algorithm")
                                hasPrivateKey = false
                                certs.clear()
                            }
                            "PrivateKey", "Certificate" -> if (inKey) text = StringBuilder()
                        }
                    }
                    XmlPullParser.TEXT -> text?.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "PrivateKey" -> {
                                if (text?.isNotBlank() == true) hasPrivateKey = true
                                text = null
                            }
                            "Certificate" -> {
                                text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                                    ?.let { certs += it }
                                text = null
                            }
                            "Key" -> {
                                inKey = false
                                val alg = algorithm
                                if (alg != null && hasPrivateKey && certs.isNotEmpty()) {
                                    keys += ParsedKey(alg, certs.toList())
                                }
                            }
                        }
                        if (--depth == 0) rootClosed = true
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            if (!rootClosed || keys.isEmpty()) throw e
        }
        return keys
    }

    private fun parseCertificate(pem: String): X509Certificate? = try {
        val body = pem
            .replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("\\s"), "")
        val der = Base64.decode(body, Base64.DEFAULT)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    } catch (_: Exception) {
        null
    }

    private fun normaliseAlgorithm(algorithm: String): String = when (algorithm.lowercase()) {
        "ec", "ecdsa" -> "ECDSA"
        "rsa" -> "RSA"
        else -> algorithm.uppercase()
    }

    enum class SecurityLevel { STRONGBOX, TEE, SOFTWARE, UNKNOWN }

    enum class ManufacturerSourceKind {
        RKP,
        SUBJECT,
        NONE,
    }

    data class CertDetails(
        val subject: String,
        val issuer: String,
        val serialHex: String,
        val notBefore: Long,
        val notAfter: Long,
        val publicKey: String,
        val isRoot: Boolean,
    )

    data class RkpInfo(
        val manufacturer: String?,
        val validatedEntity: String?,
        val certsIssued: Int?,
        val issue: String?,
    )

    data class KeyDetails(
        val algorithm: String,
        val certs: List<CertDetails>,
        val securityLevel: SecurityLevel,
        val rootLabel: String?,
        val rkp: RkpInfo?,
    )

    data class Result(
        val keys: List<KeyDetails>,
        val manufacturer: String?,
        val manufacturerSource: ManufacturerSourceKind,
        val issuerOrganisation: String?,
        val teeVendor: String?,
        val securityLevels: List<SecurityLevel>,
        val rootLabels: List<String>,
    )

    fun analyze(xml: String): Result? {
        val chains = chains(xml)
        if (chains.isEmpty()) return null

        val keys = chains.map { (algorithm, chain) -> describeKey(algorithm, chain) }
        val allCerts = chains.flatMap { it.second }
        val nonRootSubjects = chains.flatMap { (_, chain) ->
            (if (chain.size > 1) chain.dropLast(1) else chain).map { subjectAttributes(it) }
        }

        val rkpManufacturer = keys.firstNotNullOfOrNull { it.rkp?.manufacturer?.takeIf(String::isNotBlank) }
        val subjectManufacturer = nonRootSubjects.firstNotNullOfOrNull { attrs ->
            matchMarker(attrs.joinToString(" ") { it.second }, OEM_MARKERS)
        }
        val manufacturer = rkpManufacturer ?: subjectManufacturer
        val source = when {
            rkpManufacturer != null -> ManufacturerSourceKind.RKP
            subjectManufacturer != null -> ManufacturerSourceKind.SUBJECT
            else -> ManufacturerSourceKind.NONE
        }
        val issuerOrganisation = nonRootSubjects.firstNotNullOfOrNull { attrs ->
            attrs.firstOrNull { it.first == OID_O }?.second?.takeIf(String::isNotBlank)
        }
        val teeVendor = allCerts.firstNotNullOfOrNull { cert ->
            matchMarker(subjectAttributes(cert).joinToString(" ") { it.second }, TEE_VENDOR_MARKERS)
        }

        return Result(
            keys = keys,
            manufacturer = manufacturer,
            manufacturerSource = source,
            issuerOrganisation = issuerOrganisation,
            teeVendor = teeVendor,
            securityLevels = keys.map { it.securityLevel }.distinct(),
            rootLabels = keys.mapNotNull { it.rootLabel }.distinct(),
        )
    }

    private fun describeKey(algorithm: String, chain: List<X509Certificate>): KeyDetails {
        val root = chain.last()
        val rootSelfSigned = runCatching { root.verify(root.publicKey) }.isSuccess
        val rootLabel = if (chain.size > 1 && rootSelfSigned) {
            GOOGLE_ROOT_LABELS[sha256Hex(root.encoded)]
        } else null
        val certs = chain.mapIndexed { index, cert ->
            CertDetails(
                subject = formatName(cert.subjectX500Principal.encoded),
                issuer = formatName(cert.issuerX500Principal.encoded),
                serialHex = cert.serialNumber.toString(16),
                notBefore = cert.notBefore.time,
                notAfter = cert.notAfter.time,
                publicKey = describePublicKey(cert),
                isRoot = index == chain.lastIndex && chain.size > 1,
            )
        }
        return KeyDetails(
            algorithm = algorithm,
            certs = certs,
            securityLevel = securityLevelOf(chain),
            rootLabel = rootLabel,
            rkp = rkpInfoOf(chain),
        )
    }

    // Intermediates carry title=StrongBox or title=TEE.
    private fun securityLevelOf(chain: List<X509Certificate>): SecurityLevel {
        val subjects = chain.map { subjectAttributes(it).joinToString(" ") { a -> a.second } }
        if (subjects.any { it.contains("SOFTWARE ATTESTATION", ignoreCase = true) }) {
            return SecurityLevel.SOFTWARE
        }
        val intermediates = if (chain.size > 2) subjects.drop(1).dropLast(1) else emptyList()
        val candidates = intermediates.ifEmpty { subjects.dropLast(1).ifEmpty { subjects } }
        return when {
            candidates.any { it.contains("STRONGBOX", ignoreCase = true) } -> SecurityLevel.STRONGBOX
            candidates.any { Regex("\\bTEE\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } ->
                SecurityLevel.TEE
            else -> SecurityLevel.UNKNOWN
        }
    }

    private fun rkpInfoOf(chain: List<X509Certificate>): RkpInfo? {
        val raw = chain.reversed().firstNotNullOfOrNull { it.getExtensionValue(PROVISIONING_INFO_OID) }
            ?: return null
        if (raw.size > 64 * 1024) return RkpInfo(null, null, null, "Provisioning info exceeded 64 KiB")
        val payload = try {
            val tlv = Der.read(raw, 0)
            require(tlv.tag == 0x04 && tlv.end == raw.size)
            raw.copyOfRange(tlv.start, tlv.end)
        } catch (_: Exception) {
            return RkpInfo(null, null, null, "Not a DER-wrapped CBOR map")
        }
        val map = try {
            Cbor(payload).readTopLevelMap()
        } catch (_: Exception) {
            return RkpInfo(null, null, null, "Not a DER-wrapped CBOR map")
        }
        val issues = mutableListOf<String>()
        val count = when (val v = map[1L]) {
            null -> { issues += "key 1 absent"; null }
            is Long -> if (v in 0..Int.MAX_VALUE.toLong()) v.toInt()
            else { issues += "key 1 out of range"; null }
            else -> { issues += "key 1 not an integer"; null }
        }
        fun text(key: Long): String? = when (val v = map[key]) {
            null -> null
            is String -> v
            else -> { issues += "key $key not a text string"; null }
        }
        val manufacturer = text(3)
        val entity = text(4)
        if (!map.containsKey(4L)) issues += "key 4 absent"
        return RkpInfo(manufacturer, entity, count, issues.takeIf { it.isNotEmpty() }?.joinToString())
    }

    private fun normalise(value: String): String =
        value.uppercase(Locale.US).filter(Char::isLetterOrDigit)

    private fun matchMarker(text: String, markers: Map<String, String>): String? {
        val normalised = normalise(text)
        return markers.entries.firstOrNull { (marker, _) ->
            // Whole word only, so "LGE" doesn't match "COLLEGE".
            if (marker.length <= 4) {
                Regex("(^|[^A-Z0-9])${marker}([^A-Z0-9]|$)")
                    .containsMatchIn(text.uppercase(Locale.US))
            } else normalised.contains(marker)
        }?.value
    }

    private fun subjectAttributes(cert: X509Certificate): List<Pair<String, String>> =
        runCatching { parseName(cert.subjectX500Principal.encoded) }.getOrDefault(emptyList())

    private fun formatName(encoded: ByteArray): String {
        val attrs = runCatching { parseName(encoded) }.getOrNull() ?: return "?"
        if (attrs.isEmpty()) return "(empty)"
        val order = listOf(OID_O, OID_OU, OID_CN, OID_TITLE, OID_SERIAL)
        return attrs.sortedBy { (oid, _) -> order.indexOf(oid).let { if (it < 0) order.size else it } }
            .joinToString(", ") { (oid, value) -> "${ATTRIBUTE_NAMES[oid] ?: oid}=$value" }
    }

    /** Name ::= SEQUENCE OF SET OF SEQUENCE { type OID, value ANY }. */
    private fun parseName(der: ByteArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val name = Der.read(der, 0)
        require(name.tag == 0x30)
        var p = name.start
        while (p < name.end) {
            val set = Der.read(der, p)
            var q = set.start
            while (q < set.end) {
                val atv = Der.read(der, q)
                val oid = Der.read(der, atv.start)
                val value = Der.read(der, oid.end)
                out += Der.oid(der, oid) to Der.string(der, value)
                q = atv.end
            }
            p = set.end
        }
        return out
    }

    private fun describePublicKey(cert: X509Certificate): String {
        val key = cert.publicKey
        return when (key) {
            is java.security.interfaces.RSAPublicKey -> "RSA-${key.modulus.bitLength()}"
            is java.security.interfaces.ECPublicKey -> "EC P-${key.params.curve.field.fieldSize}"
            else -> key.algorithm
        }
    }

    private object Der {
        class Tlv(val tag: Int, val start: Int, val end: Int)

        fun read(b: ByteArray, offset: Int): Tlv {
            var p = offset
            val tag = b[p++].toInt() and 0xFF
            require(tag and 0x1F != 0x1F)
            var len = b[p++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                require(n in 1..4)
                len = 0
                repeat(n) { len = (len shl 8) or (b[p++].toInt() and 0xFF) }
            }
            require(len >= 0 && p + len <= b.size)
            return Tlv(tag, p, p + len)
        }

        fun oid(b: ByteArray, t: Tlv): String {
            val parts = mutableListOf<Long>()
            var value = 0L
            for (i in t.start until t.end) {
                val v = b[i].toInt() and 0xFF
                value = (value shl 7) or (v and 0x7F).toLong()
                if (v and 0x80 == 0) {
                    if (parts.isEmpty()) {
                        val first = if (value < 80) value / 40 else 2
                        parts += first
                        parts += value - first * 40
                    } else parts += value
                    value = 0
                }
            }
            return parts.joinToString(".")
        }

        fun string(b: ByteArray, t: Tlv): String {
            val bytes = b.copyOfRange(t.start, t.end)
            return when (t.tag) {
                0x1E -> String(bytes, Charsets.UTF_16BE) // BMPString
                0x1C -> String(bytes, Charset.forName("UTF-32BE")) // UniversalString
                0x14 -> String(bytes, Charsets.ISO_8859_1) // TeletexString
                else -> String(bytes, Charsets.UTF_8)
            }
        }
    }

    private class Cbor(private val b: ByteArray) {
        private var p = 0

        fun readTopLevelMap(): Map<Long, Any> {
            val (major, count) = head()
            require(major == 5 && count != null)
            val out = HashMap<Long, Any>()
            repeat(count.toInt()) {
                val key = value(0)
                val v = value(0)
                if (key is Long) {
                    require(!out.containsKey(key))
                    out[key] = v
                }
            }
            require(p == b.size)
            return out
        }

        private fun head(): Pair<Int, Long?> {
            val ib = b[p++].toInt() and 0xFF
            val major = ib ushr 5
            val info = ib and 0x1F
            val arg: Long? = when {
                info < 24 -> info.toLong()
                info == 24 -> next(1)
                info == 25 -> next(2)
                info == 26 -> next(4)
                info == 27 -> next(8)
                info == 31 -> null // indefinite length
                else -> throw IllegalArgumentException("reserved")
            }
            return major to arg
        }

        private fun next(n: Int): Long {
            require(p + n <= b.size)
            var v = 0L
            repeat(n) { v = (v shl 8) or (b[p++].toLong() and 0xFF) }
            return v
        }

        private fun value(depth: Int): Any {
            require(depth < 32)
            val (major, arg) = head()
            return when (major) {
                0 -> arg!!.also { require(it >= 0) }
                1 -> -1 - arg!!
                2, 3 -> {
                    val len = arg?.toInt() ?: throw IllegalArgumentException("indefinite string")
                    require(len >= 0 && p + len <= b.size)
                    val s = b.copyOfRange(p, p + len).also { p += len }
                    if (major == 3) String(s, Charsets.UTF_8) else Unit
                }
                4, 5 -> {
                    val items = if (major == 5) 2 else 1
                    if (arg == null) {
                        while ((b[p].toInt() and 0xFF) != 0xFF) value(depth + 1)
                        p++
                    } else repeat((arg * items).toInt()) { value(depth + 1) }
                    Unit
                }
                6 -> { value(depth + 1); Unit }
                else -> Unit // simple values and floats
            }
        }
    }
}
