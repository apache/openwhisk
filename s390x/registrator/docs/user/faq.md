# Frequently Asked Questions

### Why is it registering the wrong service IP?

If you're getting an odd IP registered for services, such as `127.0.0.1`, then
Registrator was unable to detect the right IP. Since this is hard to do correctly,
it's best to always set the `-ip <address>` option to the IP you want it to be.
