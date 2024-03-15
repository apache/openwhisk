Here is the updated documentation:

# How to use these workflows

There are a few [GitHub secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets) to configure to fully leverage the build.

You can use and set the following secrets also in your fork.

## Tunnelmole and Ngrok Debugging

You can debug a GitHub Action build using [Tunnelmole](https://tunnelmole.com) or [NGROK](https://ngrok.com/). Tunnelmole is a free and open source tunneling tool, while NGROK is a popular closed source tunneling tool.

Debugging is disabled for automated builds triggered by push and pull_requests.

You can trigger a workflow run manually to enable debugging.

It will open an ssh connection to the VM and keep it up and running for one hour. The connection URL is shown in the log for `debugAction.sh`. 

You can then connect to the build VM, and debug it. You need to use a password of your choice to access it.

You can continue the build with `touch /tmp/continue`. You can abort the build with `touch /tmp/abort`.

### Tunnelmole
To use Tunnelmole, follow these installation and use instructions:
1. First install Tunnelmole. View the [Installation Guide](https://tunnelmole.com/docs/#installation). For most use cases this will be done with a single copy/pasted terminal command, but there are advanced options available including building from source, or using Tunnelmole as a dependency in JavaScript/TypeScript code.
2. Run `tmole 3000` (replace `3000` with the port number you are listening on if it is different). In the output, you'll see two URLs, one http and a https URL. It's best to use the https URL for privacy and security, otherwise, your data can be easily intercepted by various means.

### Ngrok
To use Ngrok, you need to register for a Ngrok account, using the free option, and get the NGROK Token.

Then set the following secrets:

- `NGROK_TOKEN` to the Ngrok token.
- `NGROK_PASSWORD` to a password of choice to access the build with the ssh command generated.

## Log Upload

The build uploads the logs to an s3 bucket allowing inspection of them with a browser.

To create the bucket, use the following commands:

```
LOG_BUCKET=<name-of-your-bucket>
LOG_REGION=<the-region-you-use>
aws s3 mb s3://$LOG_BUCKET --region $LOG_REGION
aws s3 website s3://$LOG_BUCKET/ --index-document index.html
aws s3api put-bucket-acl --acl public-read --bucket $LOG_BUCKET
```

To enable upload to the created bucket you need to set the following secrets:

- `LOG_BUCKET`: name of your bucket in s3 (just the name, without `s3://`); create it beforehand.
- `LOG_ACCESS_KEY_ID`: your AWS access key.
- `LOG_SECRET_ACCESS_KEY`: your AWS secret key.
- `LOG_REGION`: important: the region where your bucket is.

## Slack notification

If you want to get notifications about what happens on Slack, create an [Incoming Web Hook](https://api.slack.com/messaging/webhooks) and then set the following secret:

- `SLACK_WEBHOOK`: the incoming webhook URL provided by Slack.

