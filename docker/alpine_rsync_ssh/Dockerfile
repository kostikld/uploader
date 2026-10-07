FROM alpine:latest

# Install SSH client and rsync
RUN apk add --no-cache openssh rsync bash

RUN ssh-keygen -A && \
    echo "PermitRootLogin yes" >> /etc/ssh/sshd_config && \
    echo "PasswordAuthentication yes" >> /etc/ssh/sshd_config

RUN echo "root:pass" | chpasswd


CMD ["/usr/sbin/sshd", "-D", "-e"]
