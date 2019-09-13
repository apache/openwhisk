import http from "k6/http";
import {check, group} from "k6";

// track https://github.com/loadimpact/k6/issues/1081 memory explodes when sending all this data to influxdb.

export let options = {
    stages: [
        // {duration: "1m", target: 1},
        // {duration: "2m", target: 1},
        // {duration: "10m", target: 20},
        {duration: "1m", target: 10},
        {duration: "1m", target: 20},
        {duration: "1m", target: 20},
        {duration: "1m", target: 0},
    ],
    noConnectionReuse: true,
    userAgent: "MyK6UserAgentString/1.0",
    setupTimeout: "120s",
    tags: {
        "test-kind": "echo-test",
        "test-number": `${new Date().getTime()}`
    }
};


export function setup() {
    console.log("Test-number: " + options.tags.get("test-number")[0]);
}

export default function () {
    let res = http.get("http://localhost:8080");
    check(res, {
        "Action code ran": (r) => r.status === 200
    });
};

