package com.r3.ha.utilities

import net.corda.cliutils.*
import net.corda.core.internal.*
import net.corda.core.utilities.NetworkHostAndPort
import org.w3c.dom.Document
import picocli.CommandLine
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class ArtemisConfigurationTool : CliWrapperBase("configure-artemis", "Generate and install required Artemis broker configuration files.") {

    companion object {
        // Required when creating broker instance. Are removed from generated configuration files.
        private const val ARTEMIS_DEFAULT_USER          = "corda"
        private const val ARTEMIS_DEFAULT_USER_PASS     = "corda"

        private const val USER_ID                       = "{{USER_IDENTITY}}"
        private const val CONNECTOR_STRING              = "tcp://%s?sslEnabled=true;keyStorePath=%s;keyStorePassword=%s;trustStorePath=%s;trustStorePassword=%s"
        private const val ACCEPTOR_STRING               = "tcp://%s?tcpSendBufferSize=1048576;tcpReceiveBufferSize=1048576;protocols=CORE,AMQP;useEpoll=true;amqpCredits=1000;amqpLowCredits=300;sslEnabled=true;keyStorePath=%s;keyStorePassword=%s;trustStorePath=%s;trustStorePassword=%s;needClientAuth=true;enabledCipherSuites=TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256;enabledProtocols=TLSv1.2"
    }

    @Option(names = ["--install"], description = ["Install an Artemis instance."])
    private var createInstance = false

    @Option(names = ["--distribution"], description = ["Distribution location."])
    private var dist: Path? = null

    @Option(names = ["--path"], description = ["The path where the generated configuration files will be installed."], required = true)
    private lateinit var workingDir: Path

    @Option(names = ["--ha"], description = ["The broker's working mode. Valid values: \${COMPLETION-CANDIDATES}"])
    private val mode: HAMode = HAMode.NON_HA

    @Option(names = ["--acceptor-address"], converter = [NetworkHostAndPortConverter::class], description = ["The broker instance acceptor network address for incoming connections."], required = true)
    private lateinit var acceptorHostAndPort: NetworkHostAndPort

    @Option(names = ["--keystore"], description = ["The SSL keystore path."], required = true)
    private lateinit var keyStore: Path

    @Option(names = ["--keystore-password"], description = ["The SSL keystore password."], required = true)
    private lateinit var keyStorePass: String

    @Option(names = ["--truststore"], description = ["The SSL truststore path."], required = true)
    private lateinit var trustStore: Path

    @Option(names = ["--truststore-password"], description = ["The SSL truststore password."], required = true)
    private lateinit var trustStorePass: String

    @Option(names = ["--connectors"], converter = [NetworkHostAndPortConverter::class], split = ",", description = ["A list of network hosts and ports separated by commas representing the artemis connectors used for the Artemis HA cluster. The first entry in the list will be used by the instance configured by this tool."])
    private var connectors: List<NetworkHostAndPort> = mutableListOf()

    @Option(names = ["--user"], description = ["The X500 name of connecting users (clients). Example value: \"CN=artemis, O=Corda, L=London, C=GB\""], required = true)
    private lateinit var userX500Name: String

    override fun runProgram(): Int {
        validateConditionalOptions()

        // Create the configuration directory if it doesn't exist.
        if (Files.notExists(workingDir)) Files.createDirectory(workingDir)

        // Create Artemis instance
        if (createInstance) {
            val args = "create corda_p2p_broker --allow-anonymous --user $ARTEMIS_DEFAULT_USER --password $ARTEMIS_DEFAULT_USER_PASS -- $workingDir"
            val process = if (CordaSystemUtils.isOsWindows()) {
                ProcessBuilder("cmd.exe", "/c", "${dist!! / "bin/artemis.cmd"} $args").start()

            } else {
                ProcessBuilder("/bin/sh", "-c", "${dist!! / "bin/artemis"} $args").start()
            }

            process.waitFor()

            // If clean installation, config files will be generated in {artemis_dir}/etc, overwriting existing ones.
            workingDir /= "etc"
        }

        // Generate login.config
        (workingDir / "login.config").deleteIfExists()
        javaClass.classLoader.getResourceAsStream("login.config").copyTo(workingDir / "login.config", StandardCopyOption.REPLACE_EXISTING)

        // Generate artemis-users.properties
        "artemis-users.properties".let {
            val templateResolved = javaClass.classLoader.getResourceAsStream(it).reader().readText().replace(USER_ID, userX500Name)
            (workingDir / it).deleteIfExists()
            (workingDir / it).write(templateResolved.toByteArray(), StandardOpenOption.CREATE)
        }

        // Generate artemis-roles.properties
        "artemis-roles.properties".let {
            (workingDir / it).deleteIfExists()
            javaClass.classLoader.getResourceAsStream(it).copyTo(workingDir / it, StandardCopyOption.REPLACE_EXISTING)
        }

        generateBrokerXml()

        return ExitCodes.SUCCESS
    }

    private fun validateConditionalOptions() {
        if (createInstance) {
            require (dist != null) { printError("Attempting to create a new Artemis instance. Distribution path missing.")}
        }

        if (mode.isHa) {
            require (connectors.isNotEmpty()) { printError("Artemis instance set to $mode. Connector entries missing.") }
        }
    }

    private fun generateBrokerXml() {
        val file = (workingDir / "broker.xml").toFile()
        var journalBufferTimeoutValue = "2876000"

        if (file.exists()) {
            // If the file already exists, read the journal-buffer-timeout and configure the same in the newly generated broker.xml
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val elements = doc.getElementsByTagName("journal-buffer-timeout")
            journalBufferTimeoutValue = elements.item(0).textContent
        }

        // Generate  new one using the template.
        val template = javaClass.classLoader.getResourceAsStream("broker.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(template)

        val journalBufferTimeoutElement = doc.getElementsByTagName("journal-buffer-timeout").item(0)
        journalBufferTimeoutElement.textContent = journalBufferTimeoutValue

        // Configure acceptor.
        doc.getElementsByTagName("acceptor").item(0)?.let {
            it.textContent = String.format(ACCEPTOR_STRING, acceptorHostAndPort, keyStore, keyStorePass, trustStore, trustStorePass)
        }

        if (mode.isHa) {
            addHAPolicyConfig(doc)
            addConnectorConfig(doc)
            addClusterConnectorConfig(doc)
        }

        doc.normalizeDocument()
        (workingDir / "broker.xml").deleteIfExists()
        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        val source = DOMSource(doc)
        val result = StreamResult((workingDir / "broker.xml").toFile())
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transformer.transform(source, result)
    }

    private fun addHAPolicyConfig(doc: Document) {
        val policyConfig = if (mode == HAMode.MASTER) {
            doc.createElement("master").apply {
                val child = doc.createElement("check-for-live-server")
                child.textContent = "true"
                appendChild(child)
            }
        } else  {
            doc.createElement("slave").apply {
                val failback = doc.createElement("allow-failback")
                failback.textContent = "true"
                val journalSize = doc.createElement("max-saved-replicated-journals-size")
                journalSize.textContent = "0"
                appendChild(failback)
                appendChild(journalSize)
            }
        }

        val policyType = doc.createElement("replication")
        policyType.appendChild(policyConfig)

        val haPolicy = doc.createElement("ha-policy")
        haPolicy.appendChild(policyType)

        doc.getElementsByTagName("core").item(0).appendChild(haPolicy)
    }

    private fun addConnectorConfig(doc: Document) {
        doc.getElementsByTagName("connectors").item(0).let {
            connectors.forEachIndexed { index, address ->
                val connector = doc.createElement("connector").apply {
                    if (index == 0)
                        setAttribute("name", "netty-connector")
                    else
                        setAttribute("name", "netty-connector-peer-${index - 1}")
                    textContent = String.format(CONNECTOR_STRING, address, keyStore, keyStorePass, trustStore, trustStorePass)
                }
                it.appendChild(connector)
            }
        }
    }

    private fun addClusterConnectorConfig(doc: Document) {
        val staticConnectors = doc.createElement("static-connectors").apply {
            connectors.takeLast(connectors.size - 1).forEachIndexed { index, _ ->
                val element = doc.createElement("connector-ref").apply {
                    textContent = "netty-connector-peer-$index"
                }
                appendChild(element)
            }
        }

        val connectorRef = doc.createElement("connector-ref").apply { textContent = "netty-connector" }
        val clusterConnection = doc.createElement("cluster-connection").apply { setAttribute("name", "ha-artemis") }
        clusterConnection.appendChild(connectorRef)
        clusterConnection.appendChild(staticConnectors)
        val clusterConnections = doc.createElement("cluster-connections").apply { appendChild(clusterConnection) }
        doc.getElementsByTagName("core").item(0).appendChild(clusterConnections)
    }

    private enum class HAMode(val isHa: Boolean) {
        NON_HA(false), MASTER(true), SLAVE(true)
    }

    /**
     * Converter from network addresses in [String] format (e.g. localhost:8080) to [NetworkHostAndPort].
     */
    class NetworkHostAndPortConverter : CommandLine.ITypeConverter<NetworkHostAndPort> {
        override fun convert(value: String?): NetworkHostAndPort {
            return value?.let {
                NetworkHostAndPort.parse(value)
            } ?: throw CommandLine.TypeConversionException("Cannot parse network address: $value")
        }
    }
}

