sudo apt-get install -y software-properties-common
sudo apt-get install -y python-software-properties
sudo add-apt-repository -y ppa:cwchien/gradle 
sudo apt-get update -y
sudo apt-cache search gradle 
sudo apt-get install -y gradle-2.7 
gradle -version