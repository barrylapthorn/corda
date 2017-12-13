package net.corda.sgx.bridge.wrapper

import net.corda.sgx.enclave.ECKey
import net.corda.sgx.enclave.SgxStatus

/**
 * The result of a call to
 * [NativeWrapper.processServiceProviderDetailsAndGenerateQuote].
 */
class QuoteResult(

        /**
         * The 128-bit AES-CMAC generated by the application enclave. See
         * [sgx_ra_msg3_t](https://software.intel.com/en-us/node/709238) for
         * more details on its derivation.
         */
        val messageAuthenticationCode: ByteArray,

        /**
         * The public elliptic curve key of the application enclave (of type
         * [ECKey] downstream).
         */
        val publicKey: ByteArray,

        /**
         * Security property of the Intel SGX Platform Service. If the Intel
         * SGX Platform Service security property information is not required
         * in the remote attestation and key exchange process, this field will
         * be all zeros. The buffer is 256 bytes long.
         */
        val securityProperties: ByteArray,

        /**
         * Quote returned from sgx_get_quote. More details about how the quote
         * is derived can be found in Intel's documentation:
         * [sgx_ra_msg3_t](https://software.intel.com/en-us/node/709238)
         */
        val payload: ByteArray,

        /**
         * The result of the operation (of type [SgxStatus] downstream).
         */
        val result: Long

)
