package net.corda.sgx.bridge.system

import net.corda.sgx.attestation.entities.AttestationException
import net.corda.sgx.bridge.wrapper.NativeWrapper
import net.corda.sgx.enclave.SgxException
import net.corda.sgx.enclave.SgxStatus
import net.corda.sgx.system.ExtendedGroupIdentifier
import net.corda.sgx.system.SgxDeviceStatus
import net.corda.sgx.system.SgxSystem

/**
 * Query system properties of an SGX-enabled environment.
 */
class NativeSgxSystem : SgxSystem {

    /**
     * Check if the client platform is enabled for Intel SGX. The application
     * must be run with administrator privileges to get the status
     * successfully.
     *
     * @return The current status of the SGX device.
     */
    override fun getDeviceStatus(): SgxDeviceStatus {
        return SgxSystem.deviceStatusFromCode(NativeWrapper.getDeviceStatus())
    }

    /**
     * Get the extended Intel EPID Group the client uses by default. The key
     * used to sign a quote will be a member of the this group.
     */
    override fun getExtendedGroupIdentifier(): ExtendedGroupIdentifier {
        val result = NativeWrapper.getExtendedGroupIdentifier()
        val status = SgxSystem.statusFromCode(result.result)
        if (status != SgxStatus.SUCCESS) {
            throw SgxException(status)
        }
        return SgxSystem.extendedGroupIdentifier(result.extendedGroupIdentifier)
                ?: throw AttestationException("Invalid extended EPID group")
    }

}
