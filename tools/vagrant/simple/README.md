## Simple setup to run hello action

The following instructions were tested on Mac OS X El Capitan, Ubuntu 14.04.3 LTS and may work on Windows.

*Requirements*
- Install [Vagrant](https://www.vagrantup.com/downloads.html)

### Run Hello OpenWHisk

```
./hello
```

### Wait for hello action output
```
vagrant ssh -- wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result
{
    "message": "hello"
}
```

**Tip:** The very first build may take 10 minutes or more depending on network speed. 
If there are any build failures, it might be due to network timeouts, run `../resume_build` on the host.

### Use the wsk CLI inside the vm
```
./wsk action invoke /whisk.system/samples/echo -p message hello --blocking --result

```

### Using Vagrant vm in GUI mode (Optional)
Create vm with Desktop GUI username=vagrant password=vagrant
```
  gui=true ./hello
  gui=true vagrant reload
```

**Tip**: Ignore error message `Sub-process /usr/bin/dpkg returned an error code (1)` when 
creating Vagrant vm using `gui-true`. Remember to use `gui=true` everytime you do `vagrant resume` or `vagrant reload`, 
you can make gui enable by default by editing the `Vagrant` file


