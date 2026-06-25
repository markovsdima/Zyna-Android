package com.zyna.app.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatrixPushGatewayUrlTest {
    @Test
    fun buildsGatewayUrlFromHttpsHomeserver() {
        assertEquals(
            "https://push.example.test/_matrix/push/v1/notify",
            matrixPushGatewayUrlFromHomeserver("https://matrix.example.test")
        )
    }

    @Test
    fun buildsGatewayUrlFromBareHomeserver() {
        assertEquals(
            "https://push.example.test/_matrix/push/v1/notify",
            matrixPushGatewayUrlFromHomeserver("matrix.example.test")
        )
    }

    @Test
    fun preservesSchemeAndPort() {
        assertEquals(
            "http://push.example.test:8080/_matrix/push/v1/notify",
            matrixPushGatewayUrlFromHomeserver("http://matrix.example.test:8080")
        )
    }

    @Test
    fun rejectsSingleLabelHosts() {
        assertNull(matrixPushGatewayUrlFromHomeserver("localhost"))
    }
}
