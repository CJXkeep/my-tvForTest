package com.lizongying.mytv

import android.util.Log
import com.lizongying.mytv.Utils.getDateFormat
import com.lizongying.mytv.api.ApiClient
import com.lizongying.mytv.api.FAuth
import com.lizongying.mytv.api.FAuthService
import com.lizongying.mytv.api.FEPG
import com.lizongying.mytv.models.ProgramType
import com.lizongying.mytv.models.TVViewModel
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

object Request {
    private const val TAG = "Request"
    private var fAuthService: FAuthService = ApiClient().fAuthService

    private var tokenFH = ""

    /** 终态失败回调（重试耗尽后触发），生命周期结束需置空避免持有 Activity */
    private var requestListener: RequestListener? = null

    fun onDestroy() {
        Log.i(TAG, "onDestroy")
        requestListener = null
    }

    fun fetchData(tvModel: TVViewModel) {
        when (tvModel.getTV().programType) {
            ProgramType.F -> {
                fetchFAuth(tvModel)
            }

            ProgramType.DIRECT -> {
                tvModel.firstSource()
            }
        }
    }

    private fun fetchFAuth(tvModel: TVViewModel) {
        cancelCalls()

        val title = tvModel.getTV().title

        var qa = "HD"
        if (tokenFH != "") {
            qa = "FHD"
        }

        callFAuth = fAuthService.getAuth(tokenFH, tvModel.getTV().pid, qa)
        callFAuth?.enqueue(object : Callback<FAuth> {
            override fun onResponse(call: Call<FAuth>, response: Response<FAuth>) {
                if (response.isSuccessful && response.body()?.data?.live_url != null) {
                    val url = response.body()?.data?.live_url!!
                    Log.d(TAG, "$title url $url")
                    tvModel.addVideoUrl(url)
                    tvModel.allReady()
                    tvModel.tokenFHRetryTimes = 0
                } else {
                    Log.e(TAG, "auth status error ${response.code()}")
                    if (tvModel.tokenFHRetryTimes < tvModel.tokenFHRetryMaxTimes) {
                        tvModel.tokenFHRetryTimes++
                        fetchFAuth(tvModel)
                    } else {
                        requestListener?.onRequestFinished("$title 拉流失败")
                    }
                }
            }

            override fun onFailure(call: Call<FAuth>, t: Throwable) {
                Log.e(TAG, "auth request error $t")
                if (tvModel.tokenFHRetryTimes < tvModel.tokenFHRetryMaxTimes) {
                    tvModel.tokenFHRetryTimes++
                    fetchFAuth(tvModel)
                } else {
                    requestListener?.onRequestFinished("$title 网络错误")
                }
            }
        })
    }

    fun fetchFEPG(tvViewModel: TVViewModel) {
        val title = tvViewModel.getTV().title
        fAuthService.getEPG(tvViewModel.getTV().pid, getDateFormat("yyyyMMdd"))
            .enqueue(object : Callback<List<FEPG>> {
                override fun onResponse(
                    call: Call<List<FEPG>>,
                    response: Response<List<FEPG>>
                ) {
                    if (response.isSuccessful) {
                        val program = response.body()
                        if (program != null) {
                            tvViewModel.addFEPG(program)
                            Log.d(TAG, "$title program ${program.size}")
                        }
                    } else {
                        Log.w(TAG, "$title program error")
                    }
                }

                override fun onFailure(call: Call<List<FEPG>>, t: Throwable) {
                    Log.e(TAG, "$title program request failed $t")
                }
            })
    }

    private var callFAuth: Call<FAuth>? = null

    private fun cancelCalls() {
        callFAuth?.cancel()
    }

    interface RequestListener {
        fun onRequestFinished(message: String?)
    }

    fun setRequestListener(listener: RequestListener) {
        requestListener = listener
    }
}
