{
  description = "Kestra OSS development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
        };
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            bash
            coreutils
            curl
            docker
            docker-buildx
            docker-compose
            git
            jdk25
            nodejs_22
            openssl
            unzip
            zip
          ];

          JAVA_HOME = pkgs.jdk25.home;
          GRADLE_OPTS = "-Dorg.gradle.java.home=${pkgs.jdk25.home}";
          NODE_OPTIONS = "--max-old-space-size=4096";

          shellHook = ''
            export PATH="$JAVA_HOME/bin:$PATH"
            echo "Kestra dev shell: Java $(java -version 2>&1 | head -n 1), Node $(node --version), npm $(npm --version)"
          '';
        };
      });
}
