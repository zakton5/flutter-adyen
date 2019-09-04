package app.petleo.flutter_adyen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.adyen.checkout.base.model.PaymentMethodsApiResponse
import com.adyen.checkout.base.model.payments.Amount
import com.adyen.checkout.base.model.payments.request.*
import com.adyen.checkout.base.model.payments.response.Action
import com.adyen.checkout.card.CardConfiguration
import com.adyen.checkout.core.log.LogUtil
import com.adyen.checkout.core.log.Logger
import com.adyen.checkout.dropin.DropIn
import com.adyen.checkout.dropin.DropInConfiguration
import com.adyen.checkout.dropin.service.CallResult
import com.adyen.checkout.dropin.service.DropInService
import com.adyen.checkout.googlepay.GooglePayConfiguration
import com.adyen.checkout.redirect.RedirectComponent
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.IOException

class FlutterAdyenPlugin(private val activity: Activity) : MethodCallHandler {

    private var result: Result? = null

    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "flutter_adyen")
            channel.setMethodCallHandler(FlutterAdyenPlugin(registrar.activity()))
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "openDropIn" -> {
                val paymentMethods = call.argument<String>("paymentMethods")
                val baseUrl = call.argument<String>("baseUrl")
                val authToken = call.argument<String>("authToken")
                val merchantAccount = call.argument<String>("merchantAccount")
                val pubKey = call.argument<String>("pubKey")
                val amount = call.argument<String>("amount")
                val currency = call.argument<String>("currency")
                val reference = call.argument<String>("reference")
                val shopperReference = call.argument<String>("shopperReference")

                try {
                    val jsonObject = JSONObject(paymentMethods)
                    val paymentMethodsApiResponse = PaymentMethodsApiResponse.SERIALIZER.deserialize(jsonObject)

                    val googlePayConfig = GooglePayConfiguration.Builder(activity, merchantAccount
                            ?: "").build()
                    val cardConfiguration = CardConfiguration.Builder(activity, pubKey
                            ?: "").build()

                    val resultIntent = Intent(activity, activity::class.java)
                    resultIntent.putExtra("baseUrl", baseUrl)
                    resultIntent.putExtra("Authorization", authToken)

                    val sharedPref = activity.getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
                    with(sharedPref.edit()) {
                        putString("baseUrl", baseUrl)
                        putString("Authorization", authToken)
                        putString("merchantAccount", merchantAccount)
                        putString("amount", amount)
                        putString("currency", currency)
                        putString("reference", reference)
                        putString("shopperReference", shopperReference)
                        commit()
                    }
                    resultIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP

                    val dropInConfiguration = DropInConfiguration.Builder(activity, resultIntent, MyDropInService::class.java)
                            .addCardConfiguration(cardConfiguration)
                            .addGooglePayConfiguration(googlePayConfig)
                            .build()

                    DropIn.startPayment(activity, paymentMethodsApiResponse, dropInConfiguration)
                    this.result = result
                } catch (e: Throwable) {
                    result.success("Adyen:: Failed with this error: ${e.printStackTrace()}")
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }
}

/**
 * This is just an example on how to make network calls on the [DropInService].
 * You should make the calls to your own servers and have additional data or processing if necessary.
 */
class MyDropInService : DropInService() {

    companion object {
        private val TAG = LogUtil.getTag()
    }

    override fun makePaymentsCall(paymentComponentData: JSONObject): CallResult {

        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("baseUrl", "UNDEFINED_STR")
        val authorization = sharedPref.getString("Authorization", "UNDEFINED_STR")
        val merchantAccount = sharedPref.getString("merchantAccount", "UNDEFINED_STR")
        val amount = sharedPref.getString("amount", "UNDEFINED_STR")
        val currency = sharedPref.getString("currency", "UNDEFINED_STR")
        val reference = sharedPref.getString("reference", "UNDEFINED_STR")
        val shopperReference = sharedPref.getString("shopperReference", "UNDEFINED_STR")

        val serializedPaymentComponentData = PaymentComponentData.SERIALIZER.deserialize(paymentComponentData)

        if (serializedPaymentComponentData.paymentMethod == null) {
            return CallResult(CallResult.ResultType.ERROR, "Empty payment data")
        }

        val paymentsRequest = createPaymentsRequest(this@MyDropInService, serializedPaymentComponentData, amount, currency, merchantAccount, shopperReference, reference)
        val paymentsRequestJson = serializePaymentsRequest(paymentsRequest)

        val requestBody = RequestBody.create(MediaType.parse("application/json"), paymentsRequestJson.toString())

        val headers: HashMap<String, String> = HashMap()
        headers["Authorization"] = authorization
        val call = getService(headers, baseUrl).payments(requestBody)
        call.request().headers()
        return try {
            val response = call.execute()
            val paymentsResponse = response.body()

            if (response.isSuccessful && paymentsResponse != null) {
                if (paymentsResponse.action != null) {
                    CallResult(CallResult.ResultType.ACTION, Action.SERIALIZER.serialize(paymentsResponse.action).toString())
                } else {
                    CallResult(CallResult.ResultType.FINISHED, paymentsResponse.resultCode
                            ?: "EMPTY")
                }
            } else {
                CallResult(CallResult.ResultType.ERROR, "IOException")
            }
        } catch (e: IOException) {
            CallResult(CallResult.ResultType.ERROR, "IOException")
        }
    }

    override fun makeDetailsCall(actionComponentData: JSONObject): CallResult {

        val sharedPref = getSharedPreferences("ADYEN", Context.MODE_PRIVATE)
        val baseUrl = sharedPref.getString("baseUrl", "UNDEFINED_STR")
        val authorization = sharedPref.getString("Authorization", "UNDEFINED_STR")

        val requestBody = RequestBody.create(MediaType.parse("application/json"), actionComponentData.toString())
        val headers: HashMap<String, String> = HashMap()
        headers["Authorization"] = authorization
        val call = getService(headers, baseUrl).details(requestBody)

        return try {
            val response = call.execute()
            val detailsResponse = response.body()

            if (response.isSuccessful && detailsResponse != null) {
                if (detailsResponse.resultCode != null && detailsResponse.resultCode == "Authorised") {
                    CallResult(CallResult.ResultType.FINISHED, detailsResponse.resultCode)
                } else {
                    CallResult(CallResult.ResultType.FINISHED, detailsResponse.resultCode ?: "EMPTY")
                }
            } else {
                Logger.e(TAG, "FAILED - ${response.message()}")
                CallResult(CallResult.ResultType.ERROR, "IOException")
            }
        } catch (e: IOException) {
            Logger.e(TAG, "IOException", e)
            CallResult(CallResult.ResultType.ERROR, "IOException")
        }
    }
}


fun createPaymentsRequest(context: Context, paymentComponentData: PaymentComponentData<out PaymentMethodDetails>, amount: String, currency: String, merchant: String, reference: String?, shopperReference: String?): PaymentsRequest {
    @Suppress("UsePropertyAccessSyntax")
    return PaymentsRequest(
            paymentComponentData.getPaymentMethod() as PaymentMethodDetails,
            shopperReference ?: "NO_REFERENCE_DEFINED",
            paymentComponentData.isStorePaymentMethodEnable,
            getAmount(amount, currency),
            merchant,
            RedirectComponent.getReturnUrl(context),
            reference ?: ""
    )
}

private fun getAmount(amount: String, currency: String) = createAmount(amount.toInt(), currency)

fun createAmount(value: Int, currency: String): Amount {
    val amount = Amount()
    amount.currency = currency
    amount.value = value
    return amount
}

data class PaymentsRequest(
        val paymentMethod: PaymentMethodDetails,
        val shopperReference: String,
        val storePaymentMethod: Boolean,
        val amount: Amount,
        val merchantAccount: String,
        // unique reference of the payment
        val returnUrl: String,
        val reference: String,
        val channel: String = "android",
        val additionalData: AdditionalData = AdditionalData(allow3DS2 = "false")
)

data class AdditionalData(val allow3DS2: String = "false")

private fun serializePaymentsRequest(paymentsRequest: PaymentsRequest): JSONObject {
    Log.d("LOGGGGGGG", "serializePaymentsRequest started")

    val moshi = Moshi.Builder()
            .add(PolymorphicJsonAdapterFactory.of(PaymentMethodDetails::class.java, PaymentMethodDetails.TYPE)
                    .withSubtype(CardPaymentMethod::class.java, CardPaymentMethod.PAYMENT_METHOD_TYPE)
                    .withSubtype(IdealPaymentMethod::class.java, IdealPaymentMethod.PAYMENT_METHOD_TYPE)
                    .withSubtype(MolpayPaymentMethod::class.java, MolpayPaymentMethod.PAYMENT_METHOD_TYPE)
                    .withSubtype(EPSPaymentMethod::class.java, EPSPaymentMethod.PAYMENT_METHOD_TYPE)
                    .withSubtype(DotpayPaymentMethod::class.java, DotpayPaymentMethod.PAYMENT_METHOD_TYPE)
                    .withSubtype(EntercashPaymentMethod::class.java, EntercashPaymentMethod.PAYMENT_METHOD_TYPE)
                    .withSubtype(OpenBankingPaymentMethod::class.java, OpenBankingPaymentMethod.PAYMENT_METHOD_TYPE)
                    .withSubtype(GooglePayPaymentMethod::class.java, GooglePayPaymentMethod.PAYMENT_METHOD_TYPE)
                    .withSubtype(GenericPaymentMethod::class.java, "other")
            )
            .build()
    val jsonAdapter = moshi.adapter(PaymentsRequest::class.java)
    val requestString = jsonAdapter.toJson(paymentsRequest)
    val request = JSONObject(requestString)

    request.remove("paymentMethod")
    request.put("paymentMethod", PaymentMethodDetails.SERIALIZER.serialize(paymentsRequest.paymentMethod))

    Log.d("LOGGGGGGG", "serializePaymentsRequest done")
    return request
}