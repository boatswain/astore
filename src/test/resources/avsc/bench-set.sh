#/bin/sh
for((i=1;i<=10;i++));
do
    ab -c100 -n100000 -k -p PersonInfo.update -T 'application/octet-stream' 'http://localhost:8080/personinfo/update/1'
done
