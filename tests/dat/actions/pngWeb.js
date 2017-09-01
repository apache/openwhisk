function main() {
    var png = "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAGCAYAAAD68A/GAAAA/klEQVQYGWNgAAEHBxaG//+ZQMyyn581Pfas+cRQnf1LfF" +
        "Ljf+62smUgcUbt0FA2Zh7drf/ffMy9vLn3RurrW9e5hCU11i2azfD4zu1/DHz8TAy/foUxsXBrFzHzC7r8+M9S1vn1qxQT07" +
        "dDjL9fdemrqKxlYGT6z8AIMo6hgeUfA0PUvy9fGFh5GWK3z7vNxSWt++jX99+8SoyiGQwsW38w8PJEM7x5v5SJ8f+/xv8MDA" +
        "zffv9hevfkWjiXBGMpMx+j2awovjcMjFztDO8+7GF49LkbZDCDeXLTWnZO7qDfn1/+5jbw/8pjYWS4wZLztXnuEuYTk2M+Mz" +
        "Iw/AcA36VewaD6fzsAAAAASUVORK5CYII="

    return {
        statusCode: 200,
        headers: {'content-type': 'image/png'},
        body: png
    }
}
