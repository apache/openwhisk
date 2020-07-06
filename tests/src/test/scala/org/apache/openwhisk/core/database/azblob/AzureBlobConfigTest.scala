package org.apache.openwhisk.core.database.azblob

import com.azure.storage.common.policy.RetryPolicyType
import org.apache.openwhisk.core.ConfigKeys
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import pureconfig._
import pureconfig.generic.auto._

@RunWith(classOf[JUnitRunner])
class AzureBlobConfigTest extends FlatSpec with Matchers {

  behavior of "AzureBlobConfig"
  it should "use valid defaults for retry option" in {
    val config: AzBlobRetryConfig = loadConfigOrThrow[AzBlobRetryConfig](ConfigKeys.azBlob + ".retry-config")
    //make sure retry type is set to FIXED
    config.retryPolicyType shouldBe RetryPolicyType.FIXED
    //make sure optional secondaryHost is not set
    config.secondaryHost shouldBe None

  }
}
