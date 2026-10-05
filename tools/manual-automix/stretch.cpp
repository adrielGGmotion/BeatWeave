#include <fstream>
#include <vector>
#include <stdexcept>
#include <cstdlib>
#include "rubberband/native/vendor/rubberband/rubberband/RubberBandStretcher.h"

// Offline R3, pitch scale 1, linked stereo. Constant rate is a stated pilot choice.
int main(int argc, char **argv) {
    if (argc != 4) return 2;
    double speed = std::stod(argv[3]);
    if (!(speed >= 0.5 && speed <= 2.0)) return 2;
    std::ifstream in(argv[1], std::ios::binary | std::ios::ate);
    if (!in) return 3;
    auto bytes = in.tellg(); in.seekg(0);
    if (bytes <= 0 || bytes % 8) return 3;
    std::vector<float> interleaved(bytes / 4);
    in.read(reinterpret_cast<char *>(interleaved.data()), bytes);
    size_t count=interleaved.size()/2;
    std::vector<float> a(count), b(count);
    for(size_t i=0;i<count;++i){a[i]=interleaved[2*i];b[i]=interleaved[2*i+1];}
    using R=RubberBand::RubberBandStretcher;
    R stretch(48000,2,R::OptionProcessOffline | R::OptionEngineFiner |
        R::OptionThreadingNever | R::OptionChannelsTogether | R::OptionPitchHighQuality,1.0/speed,1.0);
    stretch.setExpectedInputDuration(count);
    stretch.setMaxProcessSize(4096);
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
    return out ? 0 : 4;
}
