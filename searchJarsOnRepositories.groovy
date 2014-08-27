@Grab(group = 'org.codehaus.groovy.modules.http-builder', module = 'http-builder', version = '0.7')

import java.util.jar.JarFile
import java.util.jar.Attributes.Name
import java.security.MessageDigest

arquivoDependencias = createDependencyFile()
artifactoryServer = null
if (args.length > 1) {
    artifactoryServer = connectsToArtifactory(args[1])
}
putDependencyDeclarationOnFile(args[0], artifactoryServer, arquivoDependencias)

def putDependencyDeclarationOnFile(diretorio, artifactoryServer, arquivoDependencias) {

    for (arquivo in getListOfConfigFiles(diretorio).findAll { it ==~ /.*jar/ }) {

        def nomeArquivo = cleanFileName(arquivo)
        def declaracaoDependencia = null
        if (artifactoryServer) {
            declaracaoDependencia = searchByChecksumOnLocalArtifactory(artifactoryServer, nomeArquivo, generateMD5(new File(arquivo)))
        }
        if (!declaracaoDependencia) {
            declaracaoDependencia = searchByChecksumInMavenCentral(generateSHA1(new File(arquivo)))
        }
        if (declaracaoDependencia) {
            arquivoDependencias.append(declaracaoDependencia + System.getProperty("line.separator"))
        } else {
            arquivoDependencias.append(arquivo + System.getProperty("line.separator"))
        }
    }
    println "dependencias salvas em ${arquivoDependencias.canonicalPath}"
}

def connectsToArtifactory(url) {
    if (!url) {
        return null
    }
    return new groovyx.net.http.HTTPBuilder(url)
}

def cleanFileName(arquivo) {
    arquivo.replace('\\', '/').split('/')[-1].replace('.jar', '')
}


def searchByChecksumOnLocalArtifactory(artifactoryServer, nomeArquivo, md5) {
    def declaracaoDependencia = null
    try {
        response = artifactoryServer.get(query: ['name': '*' + nomeArquivo + '*.jar'], contentType: 'application/json', headers: ['X-Result-Detail': 'info, properties'])
        declaracaoDependencia = searchByChecksumOnLocalArtifactory(md5, response.results)
    } catch (groovyx.net.http.ResponseParseException ex) {
        println 'maybe your server is wrong'
    } catch (groovy.json.JsonException ex) {
        println 'wrong return'
    } catch (NullPointerException ex) {
        println 'meh!'
    }
    declaracaoDependencia
}


def createDependencyFile() {
    arquivoDependencias = new File(args[0] + "/dependencies.txt")
    arquivoDependencias.delete()
    return arquivoDependencias

}

def getListOfConfigFiles(base) {
    listOfFiles = []
    new File("$base").eachFile {
        if (it.isFile()) {
            listOfFiles.add(it.canonicalPath)
        }
    }
    return listOfFiles
}


def searchByChecksumOnLocalArtifactory(checksum, json) {
    //TODO: workaround to who not uses Artifactory pro
    igual = json.find {
        md5Arquivo = new groovyx.net.http.HTTPBuilder(it.uri).get(contentType: 'application/json').checksums.md5
        md5Arquivo.equals(checksum)
    }
    if (igual) {
        return inferDependencyBy(igual.path)
    } else {
        return null
    }

}

def inferDependencyBy(path) {
    partes = path.split('/')
    return partes[0..-4].join('.').replaceFirst('.', '') + ":" + partes[-3].toString() + ':' + partes[-2].toString()
}

def generateSHA1(final file) {
    return generateChecksum(file, "SHA1")

}

def generateMD5(final file) {
    return generateChecksum(file, "MD5")
}

def generateChecksum(final file, type) {
    MessageDigest digest = MessageDigest.getInstance(type)
    int KB = 1024
    int MB = 1024 * KB
    //TODO: LAZY ! just copied both...refactor it, c'mon!
    if (type.equals("SHA1")) {
        file.eachByte(MB) { byte[] buf, int bytesRead ->
            digest.update(buf, 0, bytesRead);
        }

        def sha1Hex = new BigInteger(1, digest.digest()).toString(16).padLeft(40, '0')
        return sha1Hex

    } else {
        file.withInputStream() { is ->
            byte[] buffer = new byte[8192]
            int read = 0
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        def bigInt = new BigInteger(1, digest.digest()).toString(16).padLeft(32, '0')
        return bigInt
    }
}

def searchByChecksumInMavenCentral(sha1Checksum) {
    def mavenCentral = new groovyx.net.http.HTTPBuilder('http://search.maven.org/solrsearch/select?q=1:%22' + sha1Checksum + '%22&rows=20&wt=json')
    def response = null
    try {
        response = mavenCentral.get(contentType: 'application/json')
    } catch (Exception e) {
        println 'meh central!'
    }
    if (response.response.numFound > 0) {
        return response.response.docs[0].id
    } else {
        return null
    }

}