Make sure the Server.java and credentials.txt are in the same directory.
cd this directory
compile: javac Server.java
execute: java Server server_port block_duration timeout
It is recommended that server_port > 1023 because if < 1023 you need to do root authorization.

Client.java file can be in any directory.
cd this directory
compile: javac Client.java
execute: java Client server_IP server_port

After login, the supported commands in Client are:
message <user> <message>
broadcast <message>
whoelse
whoelsesince <time> //time should be in seconds
block <user>
unblock <user>
logout
startprivate <user>
private <user> <message>
topprivate <user>

All the commands are case sensitive.
All the parameters in commands are divided by space.
Messages are allowed to contain sapce.
Space ahead of command do not affect anything.