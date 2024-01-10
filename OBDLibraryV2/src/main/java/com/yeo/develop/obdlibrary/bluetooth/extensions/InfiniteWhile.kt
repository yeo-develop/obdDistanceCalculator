package com.yeo.develop.obdlibrary.bluetooth.extensions

suspend fun whileIndexedWithSuspendAction(stopCondition: () -> Boolean,  action: suspend (Int) -> Unit) {
    var actionCount = 0
    while (true) {
        if(stopCondition()) {
            break
        }
        action(actionCount++)
    }
}

fun whileIndexed(stopCondition: () -> Boolean, action: (Int) -> Unit) {
    var actionCount = 0
    while (true) {
        if(stopCondition()) {
            break
        }
        action(actionCount++)
    }

}