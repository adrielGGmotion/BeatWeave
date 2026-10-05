#include <fstream>
#include <vector>
#include <map>
#include <cmath>
#include <stdexcept>
#include <iostream>
#include "rubberband/native/vendor/rubberband/rubberband/RubberBandStretcher.h"

// Offline diagnostic renderer. This does not replace the production clock gates.
int main(int argc, char **argv) {
    try {
        if (argc != 5) return 2;
        const size_t outputFrames=std::stoull(argv[3]);
        std::ifstream in(argv[1],std::ios::binary|std::ios::ate);
        if (!in) return 3;
        auto bytes=in.tellg();in.seekg(0);
        if (bytes<=0 || bytes%8) return 3;
        std::vector<float> interleaved(bytes/4);
        in.read(reinterpret_cast<char*>(interleaved.data()),bytes);
        if (!in) return 3;
        const size_t count=interleaved.size()/2;
        const double ratio=double(outputFrames)/count;
        if (!(ratio>=.5 && ratio<=2.)) throw std::runtime_error("Overall ratio out of range");
        std::ifstream anchors(argv[4]);std::map<size_t,size_t> mapping;
        size_t src,dst,lastSrc=0,lastDst=0;bool first=true;
        if (!anchors) return 3;
        while (anchors>>src>>dst) {
            if (src>=count || dst>=outputFrames) throw std::runtime_error("Anchor outside audio");
            if (!first) {
                if (src<=lastSrc || dst<=lastDst) throw std::runtime_error("Non-monotone anchors");
                double local=double(dst-lastDst)/(src-lastSrc);
                if (local<.5 || local>2.) throw std::runtime_error("Local ratio out of range");
            }
            // R3 computes the initial ratio as first-output / first-input.
            // The origin is implicit; including (0,0) produces 0/0 upstream.
            if (src==0 && dst!=0) throw std::runtime_error("Nonzero output at source origin");
            if (src!=0) mapping[src]=dst;
            lastSrc=src;lastDst=dst;first=false;
        }
        if (!anchors.eof() || mapping.empty()) throw std::runtime_error("Invalid anchor file");
        std::vector<float> a(count),b(count);
        for(size_t i=0;i<count;++i){a[i]=interleaved[2*i];b[i]=interleaved[2*i+1];}
        using R=RubberBand::RubberBandStretcher;
#ifdef BEATWEAVE_MAP_ENGINE_R2
        // R3's local-ratio map updates can accumulate drift on dense changing
        // maps. R2 calculates a complete offline stretch profile from anchors.
        constexpr auto engine=R::OptionEngineFaster;
#else
        constexpr auto engine=R::OptionEngineFiner;
#endif
        R stretch(48000,2,R::OptionProcessOffline|engine|
            R::OptionThreadingNever|R::OptionChannelsTogether|R::OptionPitchHighQuality,ratio,1.);
        stretch.setExpectedInputDuration(count);stretch.setMaxProcessSize(4096);
        stretch.setKeyFrameMap(mapping);
        for(size_t i=0;i<count;i+=4096){
            size_t n=std::min(size_t(4096),count-i);const float* p[]={a.data()+i,b.data()+i};
            stretch.study(p,n,i+n==count);
        }
        std::ofstream out(argv[2],std::ios::binary);
        std::vector<float> oa(8192),ob(8192),mixed(16384);float* dest[]={oa.data(),ob.data()};
        for(size_t i=0;i<count;i+=4096){
            size_t n=std::min(size_t(4096),count-i);const float* p[]={a.data()+i,b.data()+i};
            stretch.process(p,n,i+n==count);
            while(stretch.available()>0){
                size_t got=stretch.retrieve(dest,std::min(8192,stretch.available()));
                for(size_t j=0;j<got;++j){mixed[2*j]=oa[j];mixed[2*j+1]=ob[j];}
                out.write(reinterpret_cast<char*>(mixed.data()),got*8);
            }
        }
        return out?0:4;
    } catch(const std::exception &e) {std::cerr<<e.what()<<'\n';return 2;}
}
