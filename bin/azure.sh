set -eux
curl -sLO https://github.com/shyiko/jabba/raw/master/install.sh
chmod +x install.sh
./install.sh
source ~/.jabba/jabba.sh
jabba install adopt@1.8.0-222
jabba use adopt@1.8.0-222
java -version
bin/test.sh unit/test
