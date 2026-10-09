package com.aliothmoon.maameow.remote.internal.display

import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.aliothmoon.maameow.third.wrappers.ServiceManager

/** 创建 VDM 独立显示，并协调关联、身份切换和失败清理。 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal object VdmDisplayFactory {
    fun prepare(name: String, width: Int, height: Int, dpi: Int): VdmDisplaySession {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            "VDM requires Android 14"
        }
        check(Process.myUid() == Process.ROOT_UID || Process.myUid() == Process.SHELL_UID) {
            "VDM helper requires root or shell identity"
        }

        val address = CompanionAssociation.address()
        try {
            val association = CompanionAssociation.ensure(address)
            return createForAssociation(
                address = address,
                associationId = association.id,
                name = name,
                width = width,
                height = height,
                dpi = dpi,
            )
        } catch (failure: IdentityRestoreError) {
            // 进程已请求终止，不能在有效身份未知时继续执行 Binder 或 shell 清理。
            throw failure
        } catch (failure: OwnerThreadAbandonedException) {
            // 清理责任已交给主线程迟到任务，不能在这里解除仍可能被使用的关联。
            throw failure
        } catch (failure: Throwable) {
            try {
                CompanionAssociation.remove(address)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private fun createForAssociation(
        address: String,
        associationId: Int,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
    ): VdmDisplaySession {
        val resources = BinderIdentityRunner.onOwnerThread(
            action = {
                var createdDevice: VirtualDeviceHandle? = null
                try {
                    BinderIdentityRunner.withShellIdentity {
                        val device = AndroidVirtualDeviceApi.createVirtualDevice(associationId)
                        createdDevice = device
                        val display = AndroidVirtualDeviceApi.createVirtualDisplay(
                            device = device,
                            name = name,
                            width = width,
                            height = height,
                            dpi = dpi,
                        )
                        CreatedDisplay(device, display)
                    }
                } catch (failure: IdentityRestoreError) {
                    throw failure
                } catch (failure: Throwable) {
                    createdDevice?.let { device ->
                        try {
                            device.close()
                        } catch (cleanupFailure: Throwable) {
                            failure.addSuppressed(cleanupFailure)
                        }
                    }
                    throw failure
                }
            },
            onAbandoned = { abandoned ->
                releaseCreatedDisplay(abandoned)
                CompanionAssociation.remove(address)
            },
            onAbandonedFailure = {
                CompanionAssociation.remove(address)
            },
        )

        try {
            val displayId = resources.display.display.displayId
            val groupId = ServiceManager.getDisplayManager().getDisplayGroupId(displayId)
            check(groupId > 0) {
                "VDM display remained in group $groupId"
            }
            return VdmDisplaySession(
                address = address,
                virtualDevice = resources.virtualDevice,
                display = resources.display,
            )
        } catch (failure: Throwable) {
            try {
                releaseCreatedDisplay(resources)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private data class CreatedDisplay(
        val virtualDevice: VirtualDeviceHandle,
        val display: VirtualDisplay,
    )

    private fun releaseCreatedDisplay(resources: CreatedDisplay) {
        try {
            resources.display.setSurface(null)
            resources.display.release()
        } finally {
            resources.virtualDevice.close()
        }
    }
}
