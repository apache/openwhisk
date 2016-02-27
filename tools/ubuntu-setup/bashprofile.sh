# Adds openwhisk bin to bash profile
echo 'export PATH=$HOME/openwhisk/bin:$PATH' > "$HOME/.bash_profile"
# Adds tab completion
echo 'eval "$(register-python-argcomplete wsk)"' >> "$HOME/.bash_profile"
