# How to use those workflows

There are a few [GitHub secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets) to configure to fully leverage the build.

You can use and set the followings secrets also in your fork.

## Ngrok Debugging

You can debug a GitHub Action build using [NGROK](https://ngrok.com/).

It is disabled for automated build triggered by push and pull_requests.

You can trigger a workflow run manually  enabling ngrok debugging.

It will open an ssh connection to the VM and keep it up and running for one hour.
The connection url is showns in the log for debugAction.sh

You can then connect to the build vm, and debug it.
You need to use a password of your choice to access it.

You can continue the build with `touch /tmp/continue`.
You can abort the build with `touch /tmp/abort`.

To enable this option you have to register to Ngrok, using the fee account and get the NGROK Token.

Then set the following secrets:

- `NGROK_TOKEN` to the ngrok token.
- `NGROK_PASSWORD` to a password of choice to access the build with the ssh command generated.

## Log Upload

The build uploads the logs to an s3 bucket allowing to inspect them with a browser.

You need to create the bucket with the following commands:

```
AWS_BUCKET=<name-of-your-bucket>
AWS_REGION=<the-region-you-use>
aws s3 mb s3://$AWS_BUCKET --region $AWS_REGION
aws s3 website s3://$AWS_BUCKET/ --index-document index.html
aws s3api put-bucket-acl --acl public-read --bucket $AWS_BUCKET
```

To enable upload to the created bucket you need to set the following secrets:

- `AWS_BUCKET`: name of your bucket in s3 (just the name, without `s3://`); create it before.
- `AWS_ACCESS_KEY_ID`: your aws access key.
- `AWS_SECRET_ACCESS_KEY`: your aws secret key.
- `AWS_REGION`: important: the region where your bucket is.

## Slack notification

If you want to get notified of what happens on slack, create an [Incoming Web Hook](https://api.slack.com/messaging/webhooks) and then set the following secret:

- `SLACK_WEBHOOK`: the incoming webhook url provided by slack.
